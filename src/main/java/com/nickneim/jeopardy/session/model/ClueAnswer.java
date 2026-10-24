package com.nickneim.jeopardy.session.model;

import java.util.List;

/**
 * One player's answer to an ordinary clue or Daily Double. {@code answer} is
 * the text they submitted, or null if they ran out of time.
 * {@code phrasedAsQuestion} says whether it was phrased as a question (see
 * {@code AnswerCheck}), in every round; it's null when there's no answer.
 * {@code matchedAnswers} and {@code alsoAccepted} are set only for a correct
 * answer (see {@code AnswerCheck}).
 */
public record ClueAnswer(
        Long playerId,
        String answer,
        boolean correct,
        Boolean phrasedAsQuestion,
        List<String> matchedAnswers,
        List<String> alsoAccepted
) {}
