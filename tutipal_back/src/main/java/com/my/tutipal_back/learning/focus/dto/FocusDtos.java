package com.my.tutipal_back.learning.focus.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.my.tutipal_back.learning.focus.FocusState;

/** PT-90 request and response bodies of the focus check API. */
public final class FocusDtos {

    /** Lowercase exe basename only: no path, no upper case, at most 64 characters before ".exe". */
    public static final String EXE_NAME_PATTERN = "^[a-z0-9._-]{1,64}\\.exe$";

    private FocusDtos() {
    }

    /** One to fifteen minutes, oldest first. The server assigns the minutes; there is no user id in the body. */
    public record FocusSubmitRequest(
            @NotNull @Size(min = 1, max = 15) List<@NotNull @Valid Row> rows) {
    }

    /** One minute: the most used exe (null = unknown), idle seconds in that minute, foreground switches. */
    public record Row(
            @Pattern(regexp = EXE_NAME_PATTERN, message = "실행 파일 이름 형식이 아니에요") String exeName,
            @Min(0) @Max(60) int idleSec,
            @Min(0) @Max(120) int switchCount) {
    }

    public record FocusStatusResponse(FocusState state, Instant updatedAt) {
    }

    /** Teacher view: no exe name, category or raw values. */
    public record StudentFocus(Long userId, FocusState state, Instant updatedAt) {
    }

    /**
     * retentionDays / maxSampleAgeDays / staleAfterSeconds are the current app.focus settings, so the screen builds
     * the consent text from the values the server really applies and stops showing its own state after the same
     * stale-after the teacher view uses.
     */
    public record ConsentResponse(boolean consented, String version, Instant consentedAt, String currentVersion,
                                  int retentionDays, int maxSampleAgeDays, long staleAfterSeconds) {
    }

    public record ConsentRequest(@NotBlank @Size(max = 20) String version) {
    }
}
