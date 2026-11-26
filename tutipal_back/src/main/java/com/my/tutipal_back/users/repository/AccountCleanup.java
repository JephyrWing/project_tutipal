package com.my.tutipal_back.users.repository;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.my.tutipal_back.users.entity.User;

/**
 * 회원 탈퇴(FR-MY-02) SQL. PM 결정(2026-11-26, PT-13): 계정은 익명화하고 기록은 하나도 지우지 않는다.
 * 답안 · 게시글 · 댓글 · 도움 요청과 도움 채팅 · 참가 기록 · 내가 연 수업(안의 다른 학생 기록 포함) · 자료와 파일 ·
 * 복습 기록 · AI 대화 · 집중도 기록이 모두 남고, 화면에는 이름 대신 {@link User#WITHDRAWN_NAME} 이 보인다.
 * users 행이 남으므로 새 테이블이 users 를 참조해도 여기 추가할 것은 없다.
 */
@Repository
public class AccountCleanup {

    /**
     * provider_id 에 우리 id 를 넣어 uk_users_provider(provider, provider_id) 를 지키고 원래 소셜 id 를 지운다.
     * 소셜 로그인은 (provider, provider_id) 로 찾으므로 같은 소셜 계정으로 다시 들어오면 새 사용자가 만들어진다.
     * email 은 엔티티에 매핑돼 있지 않아(PT-14, V13 이후 항상 NULL) 여기서 함께 비운다.
     * 이미 익명화된 행은 바꾸지 않아 0을 돌려준다 (동시에 두 번 탈퇴하면 뒤의 것이 행 잠금을 기다린 뒤 0).
     */
    static final String ANONYMIZE = "UPDATE users SET provider = :withdrawn, provider_id = CAST(id AS VARCHAR(100)),"
            + " name = :name, email = NULL, role = 'USER', updated_at = CURRENT_TIMESTAMP"
            + " WHERE id = :u AND provider <> :withdrawn";

    private final JdbcClient jdbc;

    public AccountCleanup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 계정을 익명화한다. 바뀐 행 수 (0 = 없는 사용자이거나 이미 탈퇴). */
    public int anonymize(Long userId) {
        return jdbc.sql(ANONYMIZE)
                .param("withdrawn", User.WITHDRAWN_PROVIDER)
                .param("name", User.WITHDRAWN_NAME)
                .param("u", userId)
                .update();
    }

    /** 이 사람이 연 세션 중 아직 안 끝난 것 (탈퇴 때 닫으려고). */
    public List<Long> openOwnedSessionIds(Long userId) {
        return jdbc.sql("SELECT id FROM study_sessions WHERE owner_id = :u AND ended_at IS NULL ORDER BY id")
                .param("u", userId).query(Long.class).list();
    }

    /** Same session-first order as chat and help state changes (lock before closing sessions and help requests). */
    public void lockHelpSessions(Long userId) {
        jdbc.sql("SELECT id FROM study_sessions WHERE owner_id = :u OR id IN "
                + "(SELECT session_id FROM session_participants WHERE user_id = :u) ORDER BY id FOR UPDATE")
                .param("u", userId).query(Long.class).list();
    }
}
