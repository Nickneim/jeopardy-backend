package com.nickneim.jeopardy.session.dto;

/**
 * One player's Final Jeopardy status. Everyone receives every player's
 * entry, so {@code wager}, {@code answer} and {@code correct} stay null
 * until the reveal; before that only whether they've submitted is shown.
 */
public record FinalJeopardyPlayerResponse(
        Long playerId,
        boolean hasWagered,
        boolean hasAnswered,
        Integer wager,
        String answer,
        Boolean correct
) {}
