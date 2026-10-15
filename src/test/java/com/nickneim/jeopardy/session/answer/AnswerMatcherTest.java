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
