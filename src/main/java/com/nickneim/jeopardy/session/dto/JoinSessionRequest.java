package com.nickneim.jeopardy.session.dto;

import jakarta.validation.constraints.NotBlank;

public record JoinSessionRequest(
        @NotBlank String playerName
) {
}
