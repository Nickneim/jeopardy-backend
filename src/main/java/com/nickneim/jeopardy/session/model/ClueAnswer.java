package com.nickneim.jeopardy.session.model;

/**
 * One player's answer to an ordinary clue or Daily Double. {@code answer} is
 * the text they submitted, or null if they ran out of time.
 * {@code phrasedAsQuestion} says whether it was phrased as a question (see
 * {@code AnswerCheck}), in every round; it's null when there's no answer.
 */
public record ClueAnswer(
        Long playerId,
        String answer,
        boolean correct,
        Boolean phrasedAsQuestion
) {}
