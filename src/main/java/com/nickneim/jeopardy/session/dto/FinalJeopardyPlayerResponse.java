package com.nickneim.jeopardy.session.dto;

/**
 * One player's Final Jeopardy status. Everyone receives every player's
 * entry, so {@code wager}, {@code answer}, {@code correct} and
 * {@code phrasedAsQuestion} stay null until the reveal; before that only
 * whether they've submitted is shown. {@code phrasedAsQuestion} is also null
 * for a player who didn't answer.
 */
public record FinalJeopardyPlayerResponse(
        Long playerId,
        boolean hasWagered,
        boolean hasAnswered,
        Integer wager,
        String answer,
        Boolean correct,
        Boolean phrasedAsQuestion
) {}
