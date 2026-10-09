package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.model.ClueAnswer;

/**
 * An answer given to the clue in play (or the last one played), as everyone
 * sees it. It holds what the player submitted, never the stored correct
 * response, which is only sent as {@code currentClue.answer} during the
 * answer reveal.
 */
public record ClueAnswerResponse(
        Long playerId,
        String answer,
        boolean correct
) {

    public static ClueAnswerResponse from(ClueAnswer clueAnswer) {
        return new ClueAnswerResponse(
                clueAnswer.playerId(),
                clueAnswer.answer(),
                clueAnswer.correct()
        );
    }
}
