package com.nickneim.jeopardy.session.model;

import com.nickneim.jeopardy.session.answer.AnswerCheck;

import java.util.List;

/**
 * One player's answer to a clue, checked once when it's given. {@code answer}
 * is the text they submitted; {@code check} is the result of checking it
 * (see {@code AnswerCheck}). Both are null if they ran out of time.
 */
public record ClueAnswer(
        Long playerId,
        String answer,
        AnswerCheck check
) {

    public static ClueAnswer timedOut(Long playerId) {
        return new ClueAnswer(playerId, null, null);
    }

    /** Whether the player ran out of time instead of answering. */
    public boolean ranOutOfTime() {
        return answer == null;
    }

    public boolean correct() {
        return check != null && check.correct();
    }

    /** Whether it was phrased as a question, in every round; null without an answer. */
    public Boolean phrasedAsQuestion() {
        return check == null ? null : check.phrasedAsQuestion();
    }

    /** Set only for a correct answer (see {@code AnswerCheck}). */
    public List<String> matchedAnswers() {
        return check == null ? null : check.matchedAnswers();
    }

    /** Set only for a correct answer (see {@code AnswerCheck}). */
    public List<String> alsoAccepted() {
        return check == null ? null : check.alsoAccepted();
    }
}
