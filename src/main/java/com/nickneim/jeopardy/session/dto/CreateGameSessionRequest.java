package com.nickneim.jeopardy.session.dto;

import jakarta.validation.constraints.NotNull;

public record CreateGameSessionRequest(
        @NotNull Long gameId
) {
}
