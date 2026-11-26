package com.my.tutipal_back.users.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.common.auth.service.ActiveSessions;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.support.AfterCommit;
import com.my.tutipal_back.livekit.service.ScreenShareService;
import com.my.tutipal_back.sessions.service.StudySessionService;
import com.my.tutipal_back.users.repository.AccountCleanup;
import com.my.tutipal_back.users.repository.AccountLocks;

/**
 * 회원 탈퇴 (FR-MY-02). PM 결정(2026-11-26, PT-13): 계정을 익명화하고 기록은 모두 남긴다 ({@link AccountCleanup}).
 * UserService 와 나눈 이유: StudySessionService 가 UserService 를 쓰므로 여기서 세션 쪽을 부르면 순환 의존이 된다.
 * <ol>
 *   <li>내 users 행을 FOR NO KEY UPDATE 로 먼저 잠근다 ({@link AccountLocks}). 수업 만들기 · 참가 · 도움 요청도 같은 행을
 *       FOR SHARE 로 먼저 잠그므로, 진행 중이던 것은 끝난 뒤 아래에서 함께 닫히고 늦게 온 것은 탈퇴 계정으로 거절된다.
 *       이미 탈퇴했거나 없는 계정이면 404 (같은 클라이언트의 두 번째 요청은 세션이 끊겨 401 이고, 404 는 동시 요청 경합 때)</li>
 *   <li>그다음 관련 수업 행을 id 순으로 잠근다 (도움 · 채팅 변경과 같은 수업 → 도움 순서)</li>
 *   <li>내가 연 진행 중 수업은 선생님 "수업 종료"와 같은 로직으로 닫는다 (ended_at 기록 + 참가자에게 ended 알림)</li>
 *   <li>내가 학생으로 낸 열린 도움 요청은 나가기와 같은 방식으로 닫는다 (DONE + helpEnded)</li>
 *   <li>계정 익명화</li>
 * </ol>
 * 커밋된 뒤 같은 사용자의 로그인 세션을 모두(다른 기기 포함) 무효화한다. 열린 STOMP·LiveKit 연결은 각 가드의 주기 검사가 닫는다.
 */
@Service
public class WithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);

    private final AccountLocks locks;
    private final AccountCleanup cleanup;
    private final StudySessionService studySessions;
    private final ScreenShareService helps;
    private final ActiveSessions sessions;

    public WithdrawalService(AccountLocks locks, AccountCleanup cleanup, StudySessionService studySessions,
                             ScreenShareService helps, ActiveSessions sessions) {
        this.locks = locks;
        this.cleanup = cleanup;
        this.studySessions = studySessions;
        this.helps = helps;
        this.sessions = sessions;
    }

    @Transactional
    public void withdraw(Long userId) {
        if (!locks.lockForWithdrawal(userId)) throw notFound();
        cleanup.lockHelpSessions(userId);
        for (Long sessionId : cleanup.openOwnedSessionIds(userId)) {
            studySessions.end(userId, sessionId);
        }
        helps.closeOpenHelpsOf(userId);
        // 위 잠금이 동시 탈퇴를 직렬화하므로 0행은 생기지 않아야 한다. 생기면 404 로 롤백 (안전망)
        if (cleanup.anonymize(userId) == 0) throw notFound();
        AfterCommit.run(() -> {
            int ended = sessions.invalidateUser(userId);
            if (ended > 0) log.info("탈퇴 사용자 로그인 세션 {}개 종료", ended);
        });
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다");
    }
}
