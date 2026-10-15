package com.nickneim.jeopardy.session.answer;

import java.util.Optional;

/**
 * Decides whether a player's response matches a stored answer.
 *
 * <p>Answers come from J-Archive, cleaned up by {@code scripts/data}:
 * alternatives and lists are "(N of) A | B | C" and anything in parentheses
 * is optional (see {@link ExpectedAnswer}). A response is lenient where a
 * player would expect it to be: case, accents, punctuation, articles, word
 * order and a few typos don't matter ({@link AnswerText}), and a list's items
 * can be given in any order with any separator, or none. Numbers must be
 * exact.
 *
 * <p>When the question form is required (Double and Final Jeopardy), the
 * response must start with one ("What is ...", see {@link QuestionForm}),
 * unless the answer is itself a question and the response says it bare. When
 * it isn't required, a question form is still allowed and ignored.
 */
public final class AnswerMatcher {

    private AnswerMatcher() {
    }

    public static boolean matches(String answer, String response, boolean questionRequired) {
        return check(answer, response, questionRequired).correct();
    }

    /** Whether the response is correct, and whether it was phrased as a question. */
    public static AnswerCheck check(String answer, String response, boolean questionRequired) {
        if (answer == null || response == null || response.isBlank()) {
            return new AnswerCheck(false, false);
        }
        ExpectedAnswer expected = ExpectedAnswer.parse(answer);

        Optional<String> afterQuestion = QuestionForm.strip(response);
        if (afterQuestion.isPresent()) {
            boolean correct = expected.isMatchedBy(AnswerText.wholeTokens(afterQuestion.get()))
                    // "Who's the Boss?" for "Who's the Boss?": the question form is part of the answer.
                    || ((!questionRequired || expected.question()) && expected.isMatchedBy(AnswerText.wholeTokens(response)));
            return new AnswerCheck(correct, true);
        }

        // Said bare. An answer that is itself a question ("Who's the Boss?"),
        // said as it is, counts as phrased as one.
        boolean saidAsItIs = expected.isMatchedBy(AnswerText.wholeTokens(response));
        boolean phrasedAsQuestion = expected.question() && saidAsItIs;
        return new AnswerCheck((!questionRequired || phrasedAsQuestion) && saidAsItIs, phrasedAsQuestion);
    }

    /**
     * True if only an exact number is accepted ("1970", "$25,000", "2/3"),
     * so a client can warn the player that typos won't be forgiven. Decades
     * ("the 1980s") don't count.
     */
    public static boolean expectsNumber(String answer) {
        return answer != null && ExpectedAnswer.parse(answer).expectsNumber();
    }
}
