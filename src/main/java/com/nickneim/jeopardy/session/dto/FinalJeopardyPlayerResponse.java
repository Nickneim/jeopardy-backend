package com.nickneim.jeopardy.session.dto;

import java.util.List;

/**
 * One player's Final Jeopardy status. Everyone receives every player's
 * entry, so {@code wager}, {@code answer}, {@code correct},
 * {@code phrasedAsQuestion}, {@code matchedAnswers} and {@code alsoAccepted}
 * stay null until the reveal; before that only whether they've submitted is
 * shown. {@code phrasedAsQuestion} is also null for a player who didn't
 * answer, and the last two are only set for a correct answer.
 */
public record FinalJeopardyPlayerResponse(
        Long playerId,
        boolean hasWagered,
        boolean hasAnswered,
        Integer wager,
        String answer,
        Boolean correct,
        Boolean phrasedAsQuestion,
        List<String> matchedAnswers,
        List<String> alsoAccepted
) {}
