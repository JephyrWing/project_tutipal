package com.my.tutipal_back.users.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.common.auth.service.ActiveSessions;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.realtime.SessionEvents;
import com.my.tutipal_back.common.support.AfterCommit;
import com.my.tutipal_back.materials.service.MaterialObjectStore;
import com.my.tutipal_back.users.repository.AccountCleanup;
import com.my.tutipal_back.users.repository.UserRepository;

/**
 * 회원 탈퇴 (FR-MY-02). UserService 와 나눈 이유: StudySessionService 가 UserService 를 쓰므로 여기서 세션·자료 쪽을
 * 부르면 순환 의존이 된다.
 * 커밋된 뒤 같은 사용자의 로그인 세션을 모두(다른 기기 포함) 무효화한다. 열린 STOMP·LiveKit 연결은 각 가드의 주기 검사가 닫는다.
 */
@Service
public class WithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);

    private final UserRepository users;
    private final AccountCleanup cleanup;
    private final SessionEvents events;
    private final MaterialObjectStore objects;
    private final ActiveSessions sessions;

    public WithdrawalService(UserRepository users, AccountCleanup cleanup, SessionEvents events, MaterialObjectStore objects,
                             ActiveSessions sessions) {
        this.users = users;
        this.cleanup = cleanup;
        this.events = events;
        this.objects = objects;
        this.sessions = sessions;
    }

    @Transactional
    public void withdraw(Long userId) {
        if (!users.existsById(userId)) throw new ApiException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다");
        cleanup.lockHelpSessions(userId);
        // 내가 연 세션의 참가자 화면이 세션 종료로 처리되게 (커밋 뒤 전송)
        for (Long sessionId : cleanup.openOwnedSessionIds(userId)) {
            events.toSession(sessionId, "ended", sessionId);
        }
        List<String> keys = cleanup.materialKeys(userId);
        cleanup.deleteAll(userId);
        AfterCommit.run(() -> {
            int ended = sessions.invalidateUser(userId);
            if (ended > 0) log.info("탈퇴 사용자 로그인 세션 {}개 종료", ended);
            keys.forEach(this::deleteFile);
        });
    }

    /** DB 는 이미 지워졌으니 파일 하나가 실패해도 나머지는 계속 지우고, 남은 키만 로그로 (운영에서 수동 정리). */
    private void deleteFile(String key) {
        try {
            objects.delete(key);
        } catch (RuntimeException e) {
            log.warn("탈퇴 자료 파일 삭제 실패 key={}", key);
        }
    }
}
