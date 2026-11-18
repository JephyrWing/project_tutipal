package com.my.tutipal_back.users.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.realtime.SessionEvents;
import com.my.tutipal_back.common.support.AfterCommit;
import com.my.tutipal_back.materials.service.MaterialObjectStore;
import com.my.tutipal_back.users.repository.AccountCleanup;
import com.my.tutipal_back.users.repository.UserRepository;

/**
 * 회원 탈퇴 (FR-MY-02). UserService 와 나눈 이유: StudySessionService 가 UserService 를 쓰므로 여기서 세션·자료 쪽을
 * 부르면 순환 의존이 된다.
 * ponytail: 지금 요청의 로그인 세션만 끊는다(Controller). 다른 기기에 남은 로그인 세션은 다음 요청에서 사용자 없음(404)이나
 *   외래키 오류로 막히고 데이터는 생기지 않는다. 모두 즉시 끊어야 하면 SessionRegistry 로 사용자별 세션을 찾아 만료시킨다.
 */
@Service
public class WithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);

    private final UserRepository users;
    private final AccountCleanup cleanup;
    private final SessionEvents events;
    private final MaterialObjectStore objects;

    public WithdrawalService(UserRepository users, AccountCleanup cleanup, SessionEvents events, MaterialObjectStore objects) {
        this.users = users;
        this.cleanup = cleanup;
        this.events = events;
        this.objects = objects;
    }

    @Transactional
    public void withdraw(Long userId) {
        if (!users.existsById(userId)) throw new ApiException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다");
        // 내가 연 세션의 참가자 화면이 세션 종료로 처리되게 (커밋 뒤 전송)
        for (Long sessionId : cleanup.openOwnedSessionIds(userId)) {
            events.toSession(sessionId, "ended", sessionId);
        }
        List<String> keys = cleanup.materialKeys(userId);
        cleanup.deleteAll(userId);
        AfterCommit.run(() -> keys.forEach(this::deleteFile));
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
