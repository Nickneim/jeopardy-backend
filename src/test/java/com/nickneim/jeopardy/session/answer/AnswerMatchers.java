package com.nickneim.jeopardy.session.answer;

/**
 * One-call shortcuts to {@link AnswerMatcher} for tests: each parses the
 * answer again, so the app keeps one matcher per clue (GameBoard) instead.
 * A null answer matches nothing.
 */
public final class AnswerMatchers {

    private AnswerMatchers() {
    }

    public static boolean matches(String answer, String response, boolean questionRequired) {
        return check(answer, response, questionRequired).correct();
    }

    public static AnswerCheck check(String answer, String response, boolean questionRequired) {
        if (answer == null) {
            return AnswerCheck.wrong(false);
        }
        return AnswerMatcher.of(answer).check(response, questionRequired);
    }

    public static AcceptedAnswers acceptedAnswers(String answer) {
        return answer == null ? null : AnswerMatcher.of(answer).acceptedAnswers();
    }

    public static boolean expectsNumber(String answer) {
        return answer != null && AnswerMatcher.of(answer).expectsNumber();
    }
}
