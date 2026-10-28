package com.nickneim.jeopardy.session.dto;

import jakarta.validation.constraints.NotNull;

/** Trebek's ruling on one player's Final Jeopardy answer. */
public record RuleFinalRequest(
        @NotNull(message = "must not be null") Long playerId,
        @NotNull(message = "must not be null") Boolean correct
) {}
