package com.my.tutipal_back.learning.conversation.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * PT-106 AI 대화 저장소 (V21: ai_conversations, ai_conversation_messages, ai_conversation_summaries, ai_memories).
 * 트랜잭션은 호출자(ConversationService)가 연다. 쓰기 전에는 lockOwned / lock 으로 대화 행을 잠근다.
 * 메시지 순서는 id(identity) 순서다. sessionId 가 null 이면 개인 학습 대화.
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

    /** 대화 행 하나만 지운다. 메시지 · 요약 · 기억은 ON DELETE CASCADE. */
    void deleteConversation(long conversationId);

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
    SavedPair insertPair(long conversationId, String question, String answer, String title, Instant now);

    /** superseded_at IS NULL AND invalidated_at IS NULL 인 요약 (최대 1개, 부분 UNIQUE). */
    Optional<Summary> activeSummary(long conversationId);

    /** 상태와 관계없이 id 로 (턴에 묶인 요약을 다시 조회할 때). */
    Optional<Summary> findSummary(long summaryId);

    void supersedeSummary(long summaryId, Instant now);

    Summary insertSummary(long conversationId, String text, long fromMessageId, long toMessageId, Instant now);

    /** 같은 (사용자, 범위) 기억, 최신부터. sessionId 가 null 이면 개인 기억만. */
    List<Memory> memories(long userId, Long sessionId, int limit);

    /** 이 대화에서 저장한 기억, 최신부터 (범위당 최대 20개라 크기가 제한된다). */
    List<Memory> memoriesOf(long conversationId);

    int countMemories(long userId, Long sessionId);

    Memory insertMemory(long userId, Long sessionId, long conversationId, String text, Instant now);

    /** 이 대화의 기억이면 지우고 true. */
    boolean deleteMemory(long conversationId, long memoryId);

    /** last_message_at < cutoff 인 대화를 최대 limit 개 지우고 지운 수를 돌려준다 (보관 기간 작업). */
    int deleteInactiveBefore(Instant cutoff, int limit);
}
