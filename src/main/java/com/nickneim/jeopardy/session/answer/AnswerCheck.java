package com.nickneim.jeopardy.session.answer;

import java.util.List;

/**
 * The result of checking a response ({@link AnswerMatcher#check}).
 *
 * @param correct           whether the response is accepted
 * @param phrasedAsQuestion whether it starts with the question form ("What is",
 *                          "whats", "waht is" ...), or is a correct response
 *                          that is itself a question said as it is ("Who's the
 *                          Boss?"). Reported in every round, also where the
 *                          question form isn't required.
 * @param matchedAnswers    for a correct response, the options it named, in the
 *                          order it named them, each in the wording it matched
 *                          ("a linen cupboard" for "a linen [closet | cupboard]");
 *                          otherwise null
 * @param alsoAccepted      for a correct response, the options of a list it
 *                          didn't name, in their first wording (empty for an
 *                          answer that isn't a list); otherwise null
 */
public record AnswerCheck(
        boolean correct,
        boolean phrasedAsQuestion,
        List<String> matchedAnswers,
        List<String> alsoAccepted
) {

    static AnswerCheck wrong(boolean phrasedAsQuestion) {
        return new AnswerCheck(false, phrasedAsQuestion, null, null);
    }
}
