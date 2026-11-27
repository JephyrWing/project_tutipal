package com.my.tutipal_back.moderation;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class ContentModerationDtos {

    private ContentModerationDtos() {
    }

    public enum Kind { POST, MATERIAL }

    public record VisibilityRequest(@NotNull Boolean hidden, @NotNull @PositiveOrZero Long version,
                                    @NotBlank @Size(max = 300) String reason) {
    }

    public record Item(long id, long sessionId, String label, Instant hiddenAt, long version) {
    }

    public record ItemPage(List<Item> items, Long nextBefore) {
    }

    public record Action(long version, long actorId, boolean hidden, String reason, Instant createdAt) {
    }
}
