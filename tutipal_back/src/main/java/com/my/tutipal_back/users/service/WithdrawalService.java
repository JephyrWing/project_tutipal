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
import com.my.tutipal_back.users.repository.UserRepository;

/**
 * 회원 탈퇴 (FR-MY-02). PM 결정(2026-11-26, PT-13): 계정을 익명화하고 기록은 모두 남긴다 ({@link AccountCleanup}).
 * UserService 와 나눈 이유: StudySessionService 가 UserService 를 쓰므로 여기서 세션 쪽을 부르면 순환 의존이 된다.
 * <ol>
 *   <li>세션 행을 먼저 잠근다 (도움 · 채팅 변경과 같은 순서, users 행 잠금은 맨 마지막)</li>
 *   <li>내가 연 진행 중 수업은 선생님 "수업 종료"와 같은 로직으로 닫는다 (ended_at 기록 + 참가자에게 ended 알림)</li>
 *   <li>내가 학생으로 낸 열린 도움 요청은 나가기와 같은 방식으로 닫는다 (DONE + helpEnded)</li>
 *   <li>계정 익명화. 이미 탈퇴한 계정은 없는 사용자처럼 404</li>
 * </ol>
 * 커밋된 뒤 같은 사용자의 로그인 세션을 모두(다른 기기 포함) 무효화한다. 열린 STOMP·LiveKit 연결은 각 가드의 주기 검사가 닫는다.
 */
@Service
public class WithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);

    private final UserRepository users;
    private final AccountCleanup cleanup;
    private final StudySessionService studySessions;
    private final ScreenShareService helps;
    private final ActiveSessions sessions;

    public WithdrawalService(UserRepository users, AccountCleanup cleanup, StudySessionService studySessions,
                             ScreenShareService helps, ActiveSessions sessions) {
        this.users = users;
        this.cleanup = cleanup;
        this.studySessions = studySessions;
        this.helps = helps;
        this.sessions = sessions;
    }

    @Transactional
    public void withdraw(Long userId) {
        // 읽기만 한다 (엔티티를 고치지 않으므로 아래 SQL 익명화를 덮어쓰지 않음)
        if (users.findById(userId).filter(user -> !user.isWithdrawn()).isEmpty()) throw notFound();
        cleanup.lockHelpSessions(userId);
        for (Long sessionId : cleanup.openOwnedSessionIds(userId)) {
            studySessions.end(userId, sessionId);
        }
        helps.closeOpenHelpsOf(userId);
        // 동시에 두 번 탈퇴하면 뒤의 요청은 0행 → 404 로 롤백된다
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
