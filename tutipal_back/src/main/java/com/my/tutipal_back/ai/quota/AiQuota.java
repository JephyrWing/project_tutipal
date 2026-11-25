package com.my.tutipal_back.ai.quota;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PT-97: 사용자 × 기능별 AI 호출 한도 (슬라이딩 윈도 기록). AI 를 부르는 모든 경로의 단일 입구는
 * {@link #reserve(long, AiFeature)} 와 {@link #run(long, AiFeature, Supplier)} 두 개뿐이다.
 * GPU 동시 실행 제한(AiSlots)과는 별개이며, 한도 검사 → 자리 대기 → 모델 호출 순서로 감싼다.
 *
 * <ul>
 *   <li>예약 순간 기록이 들어가고, 정산 전(실행 중)인 예약도 한도에 센다. 환불은 그 기록만 지운다.</li>
 *   <li>메모리에만 있으므로 서버를 재시작하면 초기화된다 (세션과 같은 전제: 재시작하면 재로그인).</li>
 *   <li>인스턴스마다 따로 센다. 서버가 N대면 실제 한도는 limit × N (AiSlots 와 같은 전제).</li>
 *   <li>여러 대 · 재시작 유지가 필요해지면 Redis 등으로 옮기면서 인터페이스를 뽑는다.</li>
 *   <li>기록은 예약 시각 기준으로 window 가 지나면 빠진다. 실행 중인 예약도 마찬가지라, window 가 호출 한 번보다
 *       짧으면(설정 실수) 끝나지 않은 호출이 한도에서 먼저 빠질 수 있다. 실제 설정은 1시간 단위라 해당 없음.</li>
 *   <li>한도가 걸린 서비스 메서드(ScreenService.describe · QuizService.generate · TutorService.chat)를 다른 AI 경로
 *       안에서 부르지 말 것 (예: PT-85 Agent 가 도구로 호출). 질문 하나가 CHAT 과 화면 · 문제 한도를 함께 쓰게 된다.
 *       Agent 가 같은 일을 하려면 한도 없는 내부 메서드를 따로 뽑아 쓴다.</li>
 * </ul>
 */
@Component
public class AiQuota {

    private record Key(long userId, AiFeature feature) {
    }

    /** 예약 한 건. equals 는 객체 동일성 → 환불할 때 정확히 이 기록만 지운다. */
    static final class Entry {
        private final Instant at;

        private Entry(Instant at) {
            this.at = at;
        }
    }

    private final AiQuotaProperties properties;
    private final Clock clock;
    /** 덱은 그 키의 compute 안에서만 읽고 쓴다 (키 단위 원자성은 ConcurrentHashMap 이 보장). */
    private final ConcurrentHashMap<Key, Deque<Entry>> log = new ConcurrentHashMap<>();

    @Autowired
    public AiQuota(AiQuotaProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AiQuota(AiQuotaProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 한 번 쓸 자리를 예약한다. 한도를 넘으면 AiQuotaExceededException (기록은 남기지 않음).
     * 받은 티켓은 반드시 run 하거나 releaseIfUnused 로 돌려줘야 한다.
     */
    public AiQuotaTicket reserve(long userId, AiFeature feature) {
        Duration window = properties.of(feature).window();
        int limit = properties.of(feature).limit();
        Instant now = clock.instant();
        Entry entry = new Entry(now);
        Instant[] retryAt = new Instant[1];

        log.compute(new Key(userId, feature), (key, entries) -> {
            Deque<Entry> live = entries == null ? new ArrayDeque<>() : entries;
            dropExpired(live, now, window);
            if (live.size() >= limit) {
                retryAt[0] = live.peekFirst().at.plus(window);
                return live;
            }
            live.addLast(entry);
            return live;
        });

        if (retryAt[0] != null) {
            throw new AiQuotaExceededException(feature, retryAt[0], retryAfterSeconds(now, retryAt[0]));
        }
        return new AiQuotaTicket(this, userId, feature, entry);
    }

    /** reserve + 실행 + 정산. 실패하면 같은 예외 객체를 그대로 다시 던진다. */
    public <T> T run(long userId, AiFeature feature, Supplier<T> work) {
        return reserve(userId, feature).run(work);
    }

    /** 모든 기록이 만료된 키를 지운다 (오래 안 쓰는 사용자 기록이 메모리에 쌓이지 않게). */
    @Scheduled(fixedDelayString = "${app.ai.quota.cleanup-interval}")
    public void evictExpired() {
        Instant now = clock.instant();
        for (Key key : log.keySet()) {
            Duration window = properties.of(key.feature()).window();
            log.computeIfPresent(key, (k, entries) -> {
                dropExpired(entries, now, window);
                return entries.isEmpty() ? null : entries;
            });
        }
    }

    int trackedKeys() {
        return log.size();
    }

    /** AiQuotaTicket.refund 에서만 부른다. */
    void remove(long userId, AiFeature feature, Entry entry) {
        log.computeIfPresent(new Key(userId, feature), (k, entries) -> {
            entries.remove(entry);
            return entries.isEmpty() ? null : entries;
        });
    }

    private static void dropExpired(Deque<Entry> entries, Instant now, Duration window) {
        while (!entries.isEmpty() && !entries.peekFirst().at.plus(window).isAfter(now)) {
            entries.pollFirst();
        }
    }

    /** 남은 시간을 초 단위로 올림 (나노초까지 보고 올림: 1.0005초 → 2초), 최소 1. */
    private static long retryAfterSeconds(Instant now, Instant retryAt) {
        Duration remaining = Duration.between(now, retryAt);
        long seconds = remaining.getSeconds();
        if (remaining.getNano() > 0) {
            seconds++;
        }
        return Math.max(1, seconds);
    }
}
