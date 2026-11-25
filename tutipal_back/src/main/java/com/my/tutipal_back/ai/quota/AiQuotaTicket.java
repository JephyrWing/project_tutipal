package com.my.tutipal_back.ai.quota;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;

import com.my.tutipal_back.ai.client.AiUnavailableException;
import com.my.tutipal_back.common.exception.ApiException;

/**
 * PT-97: AiQuota 에서 받은 예약 한 건. run 은 한 번만 쓸 수 있고, 정산(차감 확정 · 환불)도 한 번만 일어난다.
 *
 * <p>정산 규칙은 {@link #settle(boolean, Throwable)} 한 곳에만 둔다. 기준은 "모델이 GPU 로 일을 했는가" 이다.
 * <ul>
 *   <li>성공 → 차감 확정</li>
 *   <li>run 전에 끝남(권한 · 자료 검색 실패 등, releaseIfUnused) → 환불</li>
 *   <li>AiSlots 자리 대기 시간 초과(ApiException 503) → 환불: 모델을 부르지도 못했다</li>
 *   <li>첫 답변 조각 전의 AiUnavailableException (AI 서버 연결 실패 · 오류 응답 · 모델 없음 · 첫 조각 전 시간 초과)
 *       → 환불: AI 서버가 실제로 내려간 경우라 사용자 잘못이 아니다</li>
 *   <li>첫 답변 조각 뒤의 AiUnavailableException ({@link AiUnavailableException#isAfterOutput()}: 스트림 중간 오류 ·
 *       FastAPI 300초 제한 · 조각 사이 멈춤) → 차감 확정. 끝없이 생성하게 만드는 질문으로 5분 동안 GPU 를 쓰고
 *       환불받는 일(AiSlots 2자리 독점)을 막는다. 스트리밍(chatStream)과 한 번에 받기(complete) 모두 같은 규칙.</li>
 *   <li>그 밖의 실패 (화면이 끊음 · 모델 출력 형식 오류 502 · Error 등) → 차감 확정: 모델은 이미 일을 했다.
 *       화면이 끊은 것은 다음 조각을 보낼 때야 알 수 있으므로, AiSlots 대기 중에 끊은 요청도 모델이 첫 조각을 낸 뒤
 *       감지되어 차감된다 ("시작" = run 에 들어감).</li>
 * </ul>
 *
 * <p>남는 위험 (알고 둠): 첫 조각 전에 오래 걸리다 실패하면(긴 프롬프트 처리 중 180초 멈춤 등) 환불된다.
 * 503 환불은 상태 코드로만 판단하므로, run 안에서 AiSlots 말고 503 ApiException 을 던지는 코드를 부르면 그것도 환불된다
 * (현재 run 안의 화면 · 챗봇 · 문제 만들기 경로에는 없음).
 */
public final class AiQuotaTicket {

    private final AiQuota quota;
    private final long userId;
    private final AiFeature feature;
    private final AiQuota.Entry entry;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean settled = new AtomicBoolean(false);

    AiQuotaTicket(AiQuota quota, long userId, AiFeature feature, AiQuota.Entry entry) {
        this.quota = quota;
        this.userId = userId;
        this.feature = feature;
        this.entry = entry;
    }

    /**
     * 예약한 자리로 work 를 실행하고 결과에 따라 정산한다. 실패하면 같은 예외 객체를 그대로 다시 던진다.
     * 한 번만 부를 수 있다: 한 요청 안에서 LLM 을 여러 번 부르면(PT-85 Agent) 그 전체를 work 하나로 감싼다.
     */
    public <T> T run(Supplier<T> work) {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("이미 사용했거나 반납한 AI 호출 예약이에요");
        }
        T result;
        try {
            result = work.get();
        } catch (RuntimeException | Error e) {
            settle(true, e);
            throw e;
        }
        settle(true, null);
        return result;
    }

    /** run 을 한 번도 시작하지 않았으면 환불하고, 이후 run 은 막는다. 이미 run 했으면 아무것도 안 한다. */
    public void releaseIfUnused() {
        if (started.compareAndSet(false, true)) {
            settle(false, null);
        }
    }

    private void settle(boolean ran, Throwable failure) {
        if (!ran || refundable(failure)) {
            refund();
        } else {
            commit();
        }
    }

    private static boolean refundable(Throwable failure) {
        return failure instanceof AiUnavailableException down && !down.isAfterOutput()
                || failure instanceof ApiException api && api.getStatus() == HttpStatus.SERVICE_UNAVAILABLE;
    }

    /** 차감 확정. 기록은 window 가 지나면 자연히 빠진다. 두 번째부터는 아무것도 안 한다. */
    void commit() {
        settled.compareAndSet(false, true);
    }

    /** 이 예약 기록만 지운다. 이미 정산했으면 아무것도 안 한다. */
    void refund() {
        if (settled.compareAndSet(false, true)) {
            quota.remove(userId, feature, entry);
        }
    }
}
