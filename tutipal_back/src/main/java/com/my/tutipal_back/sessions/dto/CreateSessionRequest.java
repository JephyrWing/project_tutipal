package com.my.tutipal_back.sessions.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSessionRequest(
        @NotBlank @Size(max = 50) String title,
        @NotBlank @Size(max = 30) String topic) {
}
