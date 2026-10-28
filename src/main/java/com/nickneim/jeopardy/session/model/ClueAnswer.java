package com.nickneim.jeopardy.session.model;

import com.nickneim.jeopardy.session.answer.AnswerCheck;

import java.util.List;

/**
 * One player's answer to a clue, checked once when it's given. {@code answer}
 * is the text they submitted; {@code check} is the result of checking it
 * (see {@code AnswerCheck}). Both are null if they ran out of time.
 *
 * <p>In a Trebek session Trebek decides: {@code ruling} is their call, and
 * {@code check} is then made without the question-form rule, so it can say
 * what a response ruled correct matched. {@code ruling} is null when the
 * check decides (a contestant session, or before Trebek has ruled).
 */
public record ClueAnswer(
        Long playerId,
        String answer,
        AnswerCheck check,
        Boolean ruling
) {

    /** An answer the check decides. */
    public ClueAnswer(Long playerId, String answer, AnswerCheck check) {
        this(playerId, answer, check, null);
    }

    public static ClueAnswer timedOut(Long playerId) {
        return new ClueAnswer(playerId, null, null);
    }

    /** Whether the player ran out of time (or Trebek ruled before they answered). */
    public boolean ranOutOfTime() {
        return answer == null;
    }

    public boolean ruledByTrebek() {
        return ruling != null;
    }

    public boolean correct() {
        return ruling != null ? ruling : check != null && check.correct();
    }

    /** Whether it was phrased as a question, in every round; null without an answer. */
    public Boolean phrasedAsQuestion() {
        return check == null ? null : check.phrasedAsQuestion();
    }

    /**
     * Set only for a correct answer (see {@code AnswerCheck}); one Trebek
     * ruled correct only if the check matched it too.
     */
    public List<String> matchedAnswers() {
        return check == null || Boolean.FALSE.equals(ruling) ? null : check.matchedAnswers();
    }

    /** Set exactly when {@link #matchedAnswers()} is. */
    public List<String> alsoAccepted() {
        return check == null || Boolean.FALSE.equals(ruling) ? null : check.alsoAccepted();
    }
}
