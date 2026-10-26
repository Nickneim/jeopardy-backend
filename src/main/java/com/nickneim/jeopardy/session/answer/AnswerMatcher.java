package com.nickneim.jeopardy.session.answer;

import com.nickneim.jeopardy.session.answer.ExpectedAnswer.Named;

import java.util.List;
import java.util.Objects;
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
 * unless it starts with a wording that is itself a question, said bare. When
 * it isn't required, a question form is still allowed and ignored.
 */
public final class AnswerMatcher {

    private final ExpectedAnswer expected;

    private AnswerMatcher(ExpectedAnswer expected) {
        this.expected = expected;
    }

    /**
     * Parses a stored answer once, to check any number of responses against
     * it (GameBoard keeps one per clue).
     */
    public static AnswerMatcher of(String answer) {
        return new AnswerMatcher(ExpectedAnswer.parse(Objects.requireNonNull(answer, "answer")));
    }

    /** Whether the response is correct, whether it was phrased as a question, and what it named. */
    public AnswerCheck check(String response, boolean questionRequired) {
        if (response == null || response.isBlank()) {
            return AnswerCheck.wrong(false);
        }

        Optional<String> afterQuestion = QuestionForm.strip(response);
        if (afterQuestion.isPresent()) {
            List<Named> named = expected.match(AnswerText.wholeTokens(afterQuestion.get()));
            // "Who's the Boss?" for "Who's the Boss?": the question form is part of the answer.
            if (named == null) {
                List<Named> saidAsItIs = expected.match(AnswerText.wholeTokens(response));
                if (!questionRequired || startsWithQuestion(saidAsItIs)) {
                    named = saidAsItIs;
                }
            }
            return result(expected, named, true);
        }

        // Said bare. A response that starts with a wording that is itself a
        // question ("Who's the Boss?"), said as it is, counts as phrased as one.
        List<Named> saidAsItIs = expected.match(AnswerText.wholeTokens(response));
        boolean phrasedAsQuestion = startsWithQuestion(saidAsItIs);
        return result(expected, !questionRequired || phrasedAsQuestion ? saidAsItIs : null, phrasedAsQuestion);
    }

    /** The answer as players should see it. */
    public AcceptedAnswers acceptedAnswers() {
        return new AcceptedAnswers(expected.required(), expected.firstWordings());
    }

    /**
     * True if only an exact number is accepted ("1970", "$25,000", "2/3"),
     * so a client can warn the player that typos won't be forgiven. Decades
     * ("the 1980s") don't count.
     */
    public boolean expectsNumber() {
        return expected.expectsNumber();
    }

    /**
     * True if the first option a response names is worded as a question, so
     * the response starts with one ("Who's the Boss? and Taxi", not "Taxi"
     * alone, for "(1 of) Who's the Boss? | Taxi").
     */
    private static boolean startsWithQuestion(List<Named> named) {
        return named != null && named.getFirst().question();
    }

    private static AnswerCheck result(ExpectedAnswer expected, List<Named> named, boolean phrasedAsQuestion) {
        if (named == null) {
            return AnswerCheck.wrong(phrasedAsQuestion);
        }
        return new AnswerCheck(
                true,
                phrasedAsQuestion,
                named.stream().map(Named::wording).toList(),
                expected.firstWordingsExcept(named)
        );
    }
}
