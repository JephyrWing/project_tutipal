package com.my.tutipal_back.learning.conversation.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** PT-106 /api/conversations request and response bodies. Ids are strings in JSON (like HELP_CHAT). */
public final class ConversationDtos {

    private ConversationDtos() {
    }

    /** topic: required for a personal conversation; a session conversation uses the session topic. */
    public record CreateRequest(@Size(max = 30, message = "주제는 30자까지 쓸 수 있어요") String topic,
                                @Positive(message = "수업 번호가 올바르지 않아요") Long sessionId) {
    }

    /**
     * clientMessageId: UUID the screen makes per question and keeps for a resend of the same text (idempotency key).
     * Optional for older callers; without it every request is a new turn.
     */
    public record MessageRequest(@NotBlank(message = "질문을 입력해 주세요")
                                 @Size(max = 8000, message = "질문은 8000자까지 쓸 수 있어요") String content,
                                 @Pattern(regexp = UUID_PATTERN, message = "clientMessageId 형식이 올바르지 않아요")
                                 String clientMessageId) {

        public MessageRequest(String content) {
            this(content, null);
        }

        /** Parsed key (validated by the pattern above), or null when not sent. */
        public UUID clientMessageIdValue() {
            return clientMessageId == null ? null : UUID.fromString(clientMessageId);
        }
    }

    private static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    public record MemoryRequest(@NotBlank(message = "기억할 내용을 입력해 주세요")
                                @Size(max = 1000, message = "기억은 1000자까지 쓸 수 있어요") String text) {
    }

    public record ListItem(String id, String topic, String title, String sessionId, int messageCount,
                           Instant lastMessageAt) {
    }

    /** nextCursor: opaque, null on the last page. */
    public record Page(List<ListItem> items, String nextCursor, boolean hasMore) {
    }

    public record SummaryView(String text, Instant updatedAt) {
    }

    public record MemoryView(String id, String text, Instant createdAt) {
    }

    public record Detail(String id, String topic, String title, String sessionId, int messageCount, Instant createdAt,
                         Instant lastMessageAt, SummaryView summary, List<MemoryView> memories) {
    }

    public record MessageView(String id, String role, String content, Instant createdAt) {
    }

    /** items: ascending inside the page; nextBefore: pass as ?before= for the older page, null when none. */
    public record MessagePage(List<MessageView> items, String nextBefore, boolean hasMore) {
    }

    /** SSE saved event. */
    public record Saved(String userMessageId, String assistantMessageId) {
    }
}
