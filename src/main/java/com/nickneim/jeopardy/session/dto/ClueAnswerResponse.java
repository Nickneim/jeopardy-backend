package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.model.ClueAnswer;

import java.util.List;

/**
 * An answer given to the clue in play (or the last one played), as everyone
 * sees it. It holds what the player submitted, never the stored correct
 * response, which is only sent as {@code currentClue.answer} during the
 * answer reveal. {@code phrasedAsQuestion} is set in every round (null when
 * the player ran out of time), so a client can warn about a missing "What
 * is" even where it isn't required. A correct answer also says what it
 * matched ({@code matchedAnswers}, in the wording the player used) and the
 * other options of a list ({@code alsoAccepted}); a correct answer ends the
 * clue, so these never show anything before the reveal.
 */
public record ClueAnswerResponse(
        Long playerId,
        String answer,
        boolean correct,
        Boolean phrasedAsQuestion,
        List<String> matchedAnswers,
        List<String> alsoAccepted
) {

    public static ClueAnswerResponse from(ClueAnswer clueAnswer) {
        return new ClueAnswerResponse(
                clueAnswer.playerId(),
                clueAnswer.answer(),
                clueAnswer.correct(),
                clueAnswer.phrasedAsQuestion(),
                clueAnswer.matchedAnswers(),
                clueAnswer.alsoAccepted()
        );
    }
}
