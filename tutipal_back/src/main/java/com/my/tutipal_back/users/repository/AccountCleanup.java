package com.my.tutipal_back.users.repository;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 회원 탈퇴(FR-MY-02) 때 그 사람의 기록을 지운다. 여러 도메인 테이블을 외래키 순서대로 한 번에 지워야 해서 SQL 로 모았다.
 * <ul>
 *   <li>이 사람이 연 세션: 세션 안의 퀴즈·답안·공지·게시글·자료·도움 요청·참가자까지 통째로 (세션 주인이 없어지므로)</li>
 *   <li>다른 세션: 이 사람의 답안·게시글(달린 댓글 포함)·댓글·도움 요청·자료·참가 기록</li>
 *   <li>복습 기록(review_items)은 users 삭제 때 ON DELETE CASCADE</li>
 *   <li>PT-87 학생 질문 원문(chat_questions): 이 사람의 질문과 이 사람이 연 세션의 질문 전부를 명시적으로 지운다.
 *       개념(session_concepts · quiz_concepts)은 세션 · 퀴즈 삭제 때 ON DELETE CASCADE</li>
 * </ul>
 * ponytail: NFR-PRV-02 보관 기간이 미정(PT-13)이라 "탈퇴 즉시 전부 삭제"로 둠. 보관 기간이 정해지면 바로 지우지 말고
 *   익명화(author_id → 탈퇴 계정) 또는 지연 삭제 작업으로 바꾼다. 새 테이블이 users 를 참조하면 아래 목록에도 추가해야 한다.
 */
@Repository
public class AccountCleanup {

    private static final String OWNED = "(SELECT id FROM study_sessions WHERE owner_id = :u)";

    /** 외래키 순서. 자식 → 부모. */
    private static final List<String> DELETES = List.of(
            "DELETE FROM chat_questions WHERE user_id = :u OR session_id IN " + OWNED,
            "DELETE FROM comments WHERE author_id = :u",
            "DELETE FROM posts WHERE author_id = :u OR session_id IN " + OWNED,   // 남이 단 댓글은 ON DELETE CASCADE
            "DELETE FROM notices WHERE author_id = :u OR session_id IN " + OWNED,
            "DELETE FROM quiz_answers WHERE user_id = :u OR quiz_id IN (SELECT id FROM quizzes WHERE session_id IN " + OWNED + ")",
            "DELETE FROM quizzes WHERE session_id IN " + OWNED,
            "DELETE FROM help_requests WHERE user_id = :u OR accepted_by_user_id = :u OR session_id IN " + OWNED,
            "DELETE FROM materials WHERE uploader_id = :u OR session_id IN " + OWNED,   // 청크는 ON DELETE CASCADE
            "DELETE FROM session_participants WHERE user_id = :u OR session_id IN " + OWNED,
            "DELETE FROM study_sessions WHERE owner_id = :u",
            "DELETE FROM users WHERE id = :u");

    private final JdbcClient jdbc;

    public AccountCleanup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 이 사람이 연 세션 중 아직 안 끝난 것 (참가자 화면에 종료 알림을 보내려고). */
    public List<Long> openOwnedSessionIds(Long userId) {
        return jdbc.sql("SELECT id FROM study_sessions WHERE owner_id = :u AND ended_at IS NULL")
                .param("u", userId).query(Long.class).list();
    }

    /** 지울 자료의 저장소 키 (DB 커밋 뒤 파일도 지우려고). */
    public List<String> materialKeys(Long userId) {
        return jdbc.sql("SELECT storage_key FROM materials WHERE uploader_id = :u OR session_id IN " + OWNED)
                .param("u", userId).query(String.class).list();
    }

    public void deleteAll(Long userId) {
        for (String sql : DELETES) {
            jdbc.sql(sql).param("u", userId).update();
        }
    }

    /** Same session-first order as chat and help state changes; transcript deletion is atomic. */
    public void lockHelpSessions(Long userId) {
        jdbc.sql("SELECT id FROM study_sessions WHERE owner_id = :u OR id IN "
                + "(SELECT session_id FROM session_participants WHERE user_id = :u) ORDER BY id FOR UPDATE")
                .param("u", userId).query(Long.class).list();
    }
}
