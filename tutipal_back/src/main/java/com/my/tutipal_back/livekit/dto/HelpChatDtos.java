package com.my.tutipal_back.livekit.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;

public final class HelpChatDtos {
    private HelpChatDtos() {}
    public record Send(@NotNull UUID clientMessageId, @NotNull String text) {}
    // Preserve bigint precision in the JavaScript renderer.
    public record Message(String id, String helpId, String sequence, String senderId,
                          UUID clientMessageId, String text, Instant createdAt) {}
    public record Page(List<Message> items, String nextCursor, boolean hasMore, int maxLength) {}
}
