package com.nickneim.jeopardy.session.answer;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Edge cases the real answers in {@link AnswerMatcherCasesTest} don't cover. */
class AnswerMatcherTest {

    @Test
    void matches_rejectsMissingOrBlankResponses() {
        assertFalse(AnswerMatcher.matches("Lincoln", null, false));
        assertFalse(AnswerMatcher.matches("Lincoln", "", false));
        assertFalse(AnswerMatcher.matches("Lincoln", "   ", false));
        assertFalse(AnswerMatcher.matches("Lincoln", "What is", true));
        assertFalse(AnswerMatcher.matches(null, "Lincoln", false));
    }

    @Test
    void matches_answersThatAreOnlyArticlesOrSymbols() {
        assertTrue(AnswerMatcher.matches("A", "a", false));
        assertTrue(AnswerMatcher.matches("The The", "the the", false));
        assertFalse(AnswerMatcher.matches("The The", "the", false));
        assertTrue(AnswerMatcher.matches("@", "@", false));
        assertFalse(AnswerMatcher.matches("@", "#", false));
    }

    @Test
    void matches_answerThatIsAllParentheses_needsAllOfIt() {
        assertTrue(AnswerMatcher.matches("(smiling and waving)", "smiling and waving", false));
        assertFalse(AnswerMatcher.matches("(smiling and waving)", "waving", false));
    }

    @Test
    void matches_acceptsEveryFormOfTheQuestion() {
        for (String question : new String[] {
                "What is", "What's", "whats", "What are", "What're", "What was", "What were",
                "Who is", "Who's", "whos", "Where is", "Where's", "When is", "Which is", "Why is", "How is",
                "waht is", "wat is", "whta is", "wht is", "hwo is", "woh is", "wehre is", "whre is", "wher is",
                "wehn is", "whn is", "\"What is"
        }) {
            assertTrue(AnswerMatcher.matches("Lincoln", question + " Lincoln", true), question);
        }
    }

    @Test
    void matches_doesNotTakeOtherWordsForAQuestion() {
        assertFalse(AnswerMatcher.matches("Lincoln", "Whose Lincoln", true));
        assertFalse(AnswerMatcher.matches("Lincoln", "Who Lincoln", true));
        assertFalse(AnswerMatcher.matches("Lincoln", "Is Lincoln", true));
        assertFalse(AnswerMatcher.matches("Lincoln", "Lincoln?", true));
    }

    @Test
    void matches_ignoresTheQuestionFormWhenItIsNotRequired() {
        assertTrue(AnswerMatcher.matches("Lincoln", "Who is Lincoln?", false));
        assertTrue(AnswerMatcher.matches("Lincoln", "Lincoln", false));
    }

    @Test
    void check_reportsTheQuestionFormInEveryRound() {
        // Jeopardy round: optional, but still reported.
        assertEquals(new AnswerCheck(true, false), AnswerMatcher.check("Lincoln", "Lincoln", false));
        assertEquals(new AnswerCheck(true, true), AnswerMatcher.check("Lincoln", "Who is Lincoln?", false));
        // Double and Final Jeopardy: a bare answer is wrong because of it.
        assertEquals(new AnswerCheck(false, false), AnswerMatcher.check("Lincoln", "Lincoln", true));
        assertEquals(new AnswerCheck(true, true), AnswerMatcher.check("Lincoln", "Who is Lincoln?", true));
    }

    @Test
    void check_countsATypoOfTheQuestionWord() {
        assertEquals(new AnswerCheck(true, true), AnswerMatcher.check("Lincoln", "waht is Lincoln", true));
        assertEquals(new AnswerCheck(true, true), AnswerMatcher.check("Lincoln", "whats lincln", true));
    }

    @Test
    void check_reportsTheQuestionFormOfAWrongAnswerToo() {
        assertEquals(new AnswerCheck(false, true), AnswerMatcher.check("Lincoln", "Who is Washington?", true));
        assertEquals(new AnswerCheck(false, false), AnswerMatcher.check("Lincoln", "Washington", false));
    }

    @Test
    void check_answerThatIsItselfAQuestion_saidAsItIs_isPhrasedAsOne() {
        assertEquals(new AnswerCheck(true, true), AnswerMatcher.check("Who's The Boss?", "Who's the Boss?", true));
        // Without a question word the matcher recognises, said as it is (with a typo).
        assertEquals(new AnswerCheck(true, true), AnswerMatcher.check("Who's The Boss?", "Whoo's the Boss", true));
        assertEquals(new AnswerCheck(false, false), AnswerMatcher.check("Who's The Boss?", "the Boss", true));
    }

    @Test
    void check_missingResponseIsNeitherCorrectNorPhrasedAsAQuestion() {
        assertEquals(new AnswerCheck(false, false), AnswerMatcher.check("Lincoln", null, true));
        assertEquals(new AnswerCheck(false, false), AnswerMatcher.check("Lincoln", " ", true));
    }

    @Test
    void matches_bracketsAreOneThingWordedDifferently_anywhereInAnAnswer() {
        assertTrue(AnswerMatcher.matches("a linen [closet | cupboard]", "a linen cupboard", false));
        assertTrue(AnswerMatcher.matches("a linen [closet | cupboard]", "linen closet", false));
        assertFalse(AnswerMatcher.matches("a linen [closet | cupboard]", "cupboard", false));
        assertFalse(AnswerMatcher.matches("a linen [closet | cupboard]", "a linen closet cupboard", false));

        assertTrue(AnswerMatcher.matches("[Greek | Eastern] Orthodox", "Eastern Orthodox", false));
        assertFalse(AnswerMatcher.matches("[Greek | Eastern] Orthodox", "Orthodox", false));
    }

    @Test
    void matches_wordingsOfOneOptionCountOnce() {
        String answer = "(2 of) Ohio | [Myanmar | Burma]";

        assertTrue(AnswerMatcher.matches(answer, "Ohio & Myanmar", false));
        assertTrue(AnswerMatcher.matches(answer, "Burma and Ohio", false));
        assertFalse(AnswerMatcher.matches(answer, "Myanmar & Burma", false), "two wordings of one option");
        assertFalse(AnswerMatcher.matches(answer, "Ohio", false));
    }

    @Test
    void matches_pipesInsideBracketsDoNotMakeAList() {
        assertTrue(AnswerMatcher.matches("[Myanmar | Burma]", "Burma", false));
        // Naming both wordings names the same thing twice, which is fine.
        assertTrue(AnswerMatcher.matches("[Myanmar | Burma]", "Myanmar or Burma", false));
        assertFalse(AnswerMatcher.matches("[Myanmar | Burma]", "Thailand", false));
    }

    @Test
    void matches_bracketWordingsCanHaveOptionalParts() {
        String answer = "[(Abraham) Lincoln | Honest Abe]";

        assertTrue(AnswerMatcher.matches(answer, "Lincoln", false));
        assertTrue(AnswerMatcher.matches(answer, "Abraham Lincoln", false));
        assertTrue(AnswerMatcher.matches(answer, "Honest Abe", false));
        assertFalse(AnswerMatcher.matches(answer, "Abraham", false));
    }

    @Test
    void matches_aBracketWordingThatIsAQuestion_canBeSaidBare() {
        assertTrue(AnswerMatcher.matches("[Who's the Boss? | The Boss]", "Who's the Boss", true));
    }

    @Test
    void matches_unclosedParenthesesRunToTheEnd() {
        assertTrue(AnswerMatcher.matches("Lincoln (Abraham", "Lincoln", false));
        assertTrue(AnswerMatcher.matches("Lincoln (Abraham", "Abraham Lincoln", false));
    }

    @Test
    void matches_rejectsLongResponsesQuickly() {
        String response = "gold ".repeat(500);

        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertFalse(AnswerMatcher.matches("(2 of) gold | frankincense | myrrh", response, false)));
    }

    @Test
    void matches_longListResponsesStayFast() {
        String answer = "(3 of) Alaska | Wyoming | Delaware | North Dakota | South Dakota | Vermont | Montana";
        String response = "Alaska Wyoming Delaware North Dakota South Dakota Vermont Montana Alaska Wyoming Delaware";

        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertTrue(AnswerMatcher.matches(answer, response, false)));
    }

    @Test
    void expectsNumber_handlesMissingAnswers() {
        assertFalse(AnswerMatcher.expectsNumber(null));
        assertFalse(AnswerMatcher.expectsNumber("Lincoln"));
    }

    @Test
    void distance_countsASwapAsOneEdit() {
        assertEquals(1, AnswerText.distance("wright", "wrihgt", 2));
        assertEquals(1, AnswerText.distance("lincoln", "lincln", 2));
        assertEquals(3, AnswerText.distance("abc", "xyz", 2), "stops at the limit + 1");
    }
}
