package com.nickneim.jeopardy.session.dto;

import jakarta.validation.constraints.NotNull;

/** The player the host wants to remove. */
public record KickPlayerRequest(
        @NotNull Long playerId
) {}
