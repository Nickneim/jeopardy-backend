package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.model.ClueAnswer;

import java.util.List;

/**
 * The answer waiting for Trebek's ruling, with the check's suggestion:
 * {@code suggestedCorrect} is what a contestant session would have scored,
 * the question form in Double Jeopardy included. {@code answer} is null if
 * nothing was submitted.
 */
public record TrebekPendingAnswerResponse(
        Long playerId,
        String answer,
        boolean suggestedCorrect,
        Boolean phrasedAsQuestion,
        List<String> matchedAnswers
) {

    /** Returns null without a pending answer. */
    static TrebekPendingAnswerResponse from(ClueAnswer pending) {
        if (pending == null) {
            return null;
        }

        return new TrebekPendingAnswerResponse(
                pending.playerId(),
                pending.answer(),
                pending.correct(),
                pending.phrasedAsQuestion(),
                pending.matchedAnswers()
        );
    }
}
