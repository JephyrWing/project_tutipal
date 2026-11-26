package com.my.tutipal_back.learning.conversation.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PT-106 AI 대화 저장소 (V21: ai_conversations, ai_conversation_messages, ai_conversation_summaries, ai_memories).
 * 트랜잭션은 호출자(ConversationService)가 연다. 쓰기 전에는 lockOwned / lock 으로 대화 행을 잠근다.
 * 메시지 순서는 id(identity) 순서다. sessionId 가 null 이면 개인 학습 대화.
 *
 * <p>삭제: 사용자가 지운 대화 · 기억은 deleted_at 만 채운다 (soft delete). 아래 조회는 모두 살아 있는 것만 본다:
 * 지운 대화는 소유자 조회 · 잠금 · 목록에 없고, 그 대화의 메시지 · 요약 · 기억도 조회되지 않는다 (없는 것과 같다).
 * 물리 삭제는 유예 기간 뒤 purgeDeleted* 와 보관 기간 작업, 수업 삭제(FK CASCADE)뿐이다. 회원 탈퇴는 users 행을 익명화만
 * 하므로(PM 2026-11-26) 대화는 남는다.
 */
public interface ConversationStore {

    record Conversation(long id, long userId, Long sessionId, String topic, String title, int messageCount,
                        Instant createdAt, Instant lastMessageAt) {
    }

    record Message(long id, long conversationId, String role, String content, Instant createdAt) {
    }

    /** fromMessageId..toMessageId: 요약한 원본 메시지 범위. supersededAt / invalidatedAt 이 둘 다 null 이면 활성. */
    record Summary(long id, long conversationId, String text, long fromMessageId, long toMessageId, Instant createdAt,
                   Instant supersededAt, Instant invalidatedAt) {
    }

    record Memory(long id, long userId, Long sessionId, long conversationId, String text, Instant createdAt) {
    }

    /** 목록 keyset 위치: (lastMessageAt, id) 보다 뒤(오래된 쪽). */
    record Cursor(Instant lastMessageAt, long id) {
    }

    record SavedPair(Message user, Message assistant) {
    }

    Conversation insertConversation(long userId, Long sessionId, String topic, Instant now);

    Optional<Conversation> findOwned(long userId, long conversationId);

    /** SELECT ... FOR UPDATE (소유자 조건 포함). */
    Optional<Conversation> lockOwned(long userId, long conversationId);

    /**
     * 소유자 조건 없는 일반 조회 (시스템 작업용: 요약 범위 확인). 잠그지 않는다. 기본 구현은 {@link #lock} 을 쓰므로
     * DB 구현은 잠그지 않는 SELECT 로 덮어쓴다.
     */
    default Optional<Conversation> find(long conversationId) {
        return lock(conversationId);
    }

    /**
     * 같은 (사용자, 범위) 기억 저장을 트랜잭션 끝까지 한 줄로 세운다 (범위당 20개 상한의 경쟁 방지).
     * 기본 구현은 아무것도 안 한다 (메모리 구현용).
     */
    default void lockMemoryScope(long userId, Long sessionId) {
    }

    /** SELECT ... FOR UPDATE (시스템 작업용: 요약 저장). */
    Optional<Conversation> lock(long conversationId);

    /** 최근 대화부터 (last_message_at DESC, id DESC). sessionId 가 null 이면 개인 대화만. */
    List<Conversation> list(long userId, Long sessionId, Cursor after, int limit);

    /**
     * 사용자 삭제: deleted_at = now (이미 지운 대화는 그대로). 그 순간부터 메시지 · 요약 · 기억도 모든 조회에서 빠진다.
     * 물리 삭제는 {@link #purgeDeletedConversationsBefore} 가 유예 기간 뒤 한다 (ON DELETE CASCADE).
     */
    void softDeleteConversation(long conversationId, Instant now);

    /** beforeId 보다 앞의 메시지, 최신부터. beforeId 가 null 이면 마지막 메시지부터. */
    List<Message> messagesBefore(long conversationId, Long beforeId, int limit);

    /** afterMessageId 뒤의 메시지 중 마지막 limit 개, 오래된 것부터. */
    List<Message> recentMessagesAfter(long conversationId, long afterMessageId, int limit);

    int countMessagesAfter(long conversationId, long afterMessageId);

    /** afterMessageId 뒤의 메시지 중 처음 limit 개, 오래된 것부터. */
    List<Message> oldestMessagesAfter(long conversationId, long afterMessageId, int limit);

    /**
     * 질문 · 답변 한 쌍을 저장하고 message_count +2, last_message_at = now. title 은 아직 없을 때만 채운다.
     * 호출자가 대화 행을 잠근 상태여야 한다. 대화가 없으면 FK 위반(DataIntegrityViolationException).
     */
    default SavedPair insertPair(long conversationId, String question, String answer, String title, Instant now) {
        return insertPair(conversationId, question, answer, title, now, null);
    }

    /**
     * clientMessageId 가 있으면 질문 행에 저장한다. 같은 대화에 같은 값이 이미 있으면 부분 UNIQUE 위반
     * (DuplicateKeyException) 이며 트랜잭션은 되돌려진다 (message_count 도 그대로).
     */
    SavedPair insertPair(long conversationId, String question, String answer, String title, Instant now,
                         UUID clientMessageId);

    /** 이 대화에서 clientMessageId 로 저장된 질문과 바로 다음 답변. 없으면 empty. */
    Optional<SavedPair> findPairByClientMessageId(long conversationId, UUID clientMessageId);

    /** superseded_at IS NULL AND invalidated_at IS NULL 인 요약 (최대 1개, 부분 UNIQUE). */
    Optional<Summary> activeSummary(long conversationId);

    /** 상태와 관계없이 id 로 (턴에 묶인 요약을 다시 조회할 때). */
    Optional<Summary> findSummary(long summaryId);

    void supersedeSummary(long summaryId, Instant now);

    Summary insertSummary(long conversationId, String text, long fromMessageId, long toMessageId, Instant now);

    /** 같은 (사용자, 범위) 기억, 최신부터. sessionId 가 null 이면 개인 기억만. 지운 기억 · 지운 대화의 기억은 뺀다. */
    List<Memory> memories(long userId, Long sessionId, int limit);

    /** 이 대화에서 저장한 기억, 최신부터 (범위당 최대 20개라 크기가 제한된다). */
    List<Memory> memoriesOf(long conversationId);

    /** 범위의 살아 있는 기억 수 (20개 상한). memories 와 같은 조건. */
    int countMemories(long userId, Long sessionId);

    Memory insertMemory(long userId, Long sessionId, long conversationId, String text, Instant now);

    /** 살아 있는 대화의 살아 있는 기억이면 deleted_at = now 로 지우고 true (대화가 지워졌으면 false). */
    boolean softDeleteMemory(long conversationId, long memoryId, Instant now);

    /**
     * last_message_at < cutoff 인 대화를 (지운 것 포함) 최대 limit 개 물리 삭제하고 지운 수를 돌려준다 (보관 기간 작업).
     */
    int deleteInactiveBefore(Instant cutoff, int limit);

    /** deleted_at < cutoff 인 대화를 최대 limit 개 물리 삭제한다 (메시지 · 요약 · 기억은 CASCADE). */
    int purgeDeletedConversationsBefore(Instant cutoff, int limit);

    /** deleted_at < cutoff 인 기억을 최대 limit 개 물리 삭제한다. */
    int purgeDeletedMemoriesBefore(Instant cutoff, int limit);
}
