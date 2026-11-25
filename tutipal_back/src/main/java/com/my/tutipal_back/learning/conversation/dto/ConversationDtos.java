package com.my.tutipal_back.learning.conversation.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** PT-106 /api/conversations request and response bodies. Ids are strings in JSON (like HELP_CHAT). */
public final class ConversationDtos {

    private ConversationDtos() {
    }

    /** topic: required for a personal conversation; a session conversation uses the session topic. */
    public record CreateRequest(@Size(max = 30) String topic, @Positive Long sessionId) {
    }

    public record MessageRequest(@NotBlank @Size(max = 8000) String content) {
    }

    public record MemoryRequest(@NotBlank @Size(max = 1000) String text) {
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
