package com.nickneim.jeopardy.session.answer;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Edge cases the real answers in {@link AnswerMatcherCasesTest} don't cover. */
class AnswerMatcherTest {

    @Test
    void matches_rejectsMissingOrBlankResponses() {
        assertFalse(AnswerMatchers.matches("Lincoln", null, false));
        assertFalse(AnswerMatchers.matches("Lincoln", "", false));
        assertFalse(AnswerMatchers.matches("Lincoln", "   ", false));
        assertFalse(AnswerMatchers.matches("Lincoln", "What is", true));
        assertFalse(AnswerMatchers.matches(null, "Lincoln", false));
    }

    @Test
    void matches_answersThatAreOnlyArticlesOrSymbols() {
        assertTrue(AnswerMatchers.matches("A", "a", false));
        assertTrue(AnswerMatchers.matches("The The", "the the", false));
        assertFalse(AnswerMatchers.matches("The The", "the", false));
        assertTrue(AnswerMatchers.matches("@", "@", false));
        assertFalse(AnswerMatchers.matches("@", "#", false));
    }

    @Test
    void matches_answerThatIsAllParentheses_needsAllOfIt() {
        assertTrue(AnswerMatchers.matches("(smiling and waving)", "smiling and waving", false));
        assertFalse(AnswerMatchers.matches("(smiling and waving)", "waving", false));
    }

    @Test
    void matches_acceptsEveryFormOfTheQuestion() {
        for (String question : new String[] {
                "What is", "What's", "whats", "What are", "What're", "What was", "What were",
                "Who is", "Who's", "whos", "Where is", "Where's", "When is", "Which is", "Why is", "How is",
                "waht is", "wat is", "whta is", "wht is", "hwo is", "woh is", "wehre is", "whre is", "wher is",
                "wehn is", "whn is", "\"What is"
        }) {
            assertTrue(AnswerMatchers.matches("Lincoln", question + " Lincoln", true), question);
        }
    }

    @Test
    void matches_doesNotTakeOtherWordsForAQuestion() {
        assertFalse(AnswerMatchers.matches("Lincoln", "Whose Lincoln", true));
        assertFalse(AnswerMatchers.matches("Lincoln", "Who Lincoln", true));
        assertFalse(AnswerMatchers.matches("Lincoln", "Is Lincoln", true));
        assertFalse(AnswerMatchers.matches("Lincoln", "Lincoln?", true));
    }

    @Test
    void matches_ignoresTheQuestionFormWhenItIsNotRequired() {
        assertTrue(AnswerMatchers.matches("Lincoln", "Who is Lincoln?", false));
        assertTrue(AnswerMatchers.matches("Lincoln", "Lincoln", false));
    }

    @Test
    void check_reportsTheQuestionFormInEveryRound() {
        // Jeopardy round: optional, but still reported.
        assertFlags(true, false, AnswerMatchers.check("Lincoln", "Lincoln", false));
        assertFlags(true, true, AnswerMatchers.check("Lincoln", "Who is Lincoln?", false));
        // Double and Final Jeopardy: a bare answer is wrong because of it.
        assertFlags(false, false, AnswerMatchers.check("Lincoln", "Lincoln", true));
        assertFlags(true, true, AnswerMatchers.check("Lincoln", "Who is Lincoln?", true));
    }

    @Test
    void check_countsATypoOfTheQuestionWord() {
        assertFlags(true, true, AnswerMatchers.check("Lincoln", "waht is Lincoln", true));
        assertFlags(true, true, AnswerMatchers.check("Lincoln", "whats lincln", true));
    }

    @Test
    void check_reportsTheQuestionFormOfAWrongAnswerToo() {
        assertFlags(false, true, AnswerMatchers.check("Lincoln", "Who is Washington?", true));
        assertFlags(false, false, AnswerMatchers.check("Lincoln", "Washington", false));
    }

    @Test
    void check_answerThatIsItselfAQuestion_saidAsItIs_isPhrasedAsOne() {
        assertFlags(true, true, AnswerMatchers.check("Who's The Boss?", "Who's the Boss?", true));
        // Without a question word the matcher recognises, said as it is (with a typo).
        assertFlags(true, true, AnswerMatchers.check("Who's The Boss?", "Whoo's the Boss", true));
        assertFlags(false, false, AnswerMatchers.check("Who's The Boss?", "the Boss", true));
    }

    @Test
    void check_missingResponseIsNeitherCorrectNorPhrasedAsAQuestion() {
        assertFlags(false, false, AnswerMatchers.check("Lincoln", null, true));
        assertFlags(false, false, AnswerMatchers.check("Lincoln", " ", true));
    }

    @Test
    void matches_bracketsAreOneThingWordedDifferently_anywhereInAnAnswer() {
        assertTrue(AnswerMatchers.matches("a linen [closet | cupboard]", "a linen cupboard", false));
        assertTrue(AnswerMatchers.matches("a linen [closet | cupboard]", "linen closet", false));
        assertFalse(AnswerMatchers.matches("a linen [closet | cupboard]", "cupboard", false));
        assertFalse(AnswerMatchers.matches("a linen [closet | cupboard]", "a linen closet cupboard", false));

        assertTrue(AnswerMatchers.matches("[Greek | Eastern] Orthodox", "Eastern Orthodox", false));
        assertFalse(AnswerMatchers.matches("[Greek | Eastern] Orthodox", "Orthodox", false));
    }

    @Test
    void matches_wordingsOfOneOptionCountOnce() {
        String answer = "(2 of) Ohio | [Myanmar | Burma]";

        assertTrue(AnswerMatchers.matches(answer, "Ohio & Myanmar", false));
        assertTrue(AnswerMatchers.matches(answer, "Burma and Ohio", false));
        assertFalse(AnswerMatchers.matches(answer, "Myanmar & Burma", false), "two wordings of one option");
        assertFalse(AnswerMatchers.matches(answer, "Ohio", false));
    }

    @Test
    void matches_pipesInsideBracketsDoNotMakeAList() {
        assertTrue(AnswerMatchers.matches("[Myanmar | Burma]", "Burma", false));
        // Naming both wordings names the same thing twice, which is fine.
        assertTrue(AnswerMatchers.matches("[Myanmar | Burma]", "Myanmar or Burma", false));
        assertFalse(AnswerMatchers.matches("[Myanmar | Burma]", "Thailand", false));
    }

    @Test
    void matches_bracketWordingsCanHaveOptionalParts() {
        String answer = "[(Abraham) Lincoln | Honest Abe]";

        assertTrue(AnswerMatchers.matches(answer, "Lincoln", false));
        assertTrue(AnswerMatchers.matches(answer, "Abraham Lincoln", false));
        assertTrue(AnswerMatchers.matches(answer, "Honest Abe", false));
        assertFalse(AnswerMatchers.matches(answer, "Abraham", false));
    }

    @Test
    void matches_aBracketWordingThatIsAQuestion_canBeSaidBare() {
        assertTrue(AnswerMatchers.matches("[Who's the Boss? | The Boss]", "Who's the Boss", true));
    }

    @Test
    void matches_onlyAWordingThatIsAQuestion_canBeSaidBare() {
        assertFalse(AnswerMatchers.matches("[Who's the Boss? | The Boss]", "The Boss", true));
        assertTrue(AnswerMatchers.matches("[Who's the Boss? | The Boss]", "What is the Boss", true));
        assertFlags(true, true, AnswerMatchers.check("(2 of) Who's the Boss? | Taxi", "Who's the Boss and Taxi", true));
        assertFlags(false, false, AnswerMatchers.check("(2 of) Who's the Boss? | Taxi", "Taxi and Who's the Boss", true));
    }

    @Test
    void matches_unclosedParenthesesRunToTheEnd() {
        assertTrue(AnswerMatchers.matches("Lincoln (Abraham", "Lincoln", false));
        assertTrue(AnswerMatchers.matches("Lincoln (Abraham", "Abraham Lincoln", false));
    }

    @Test
    void acceptedAnswers_anUnclosedBracketDoesNotHideTheRestOfAList() {
        assertEquals(
                new AcceptedAnswers(2, List.of("Smith [Jones", "Brown", "Green")),
                AnswerMatchers.acceptedAnswers("(2 of) Smith [Jones | Brown | Green")
        );
        assertTrue(AnswerMatchers.matches("(2 of) Smith [Jones | Brown | Green", "Brown and Green", false));
    }

    // More than an int can hold: it asks for every option, like any count
    // above the number of options, instead of failing to parse.
    @Test
    void matches_aListCountTooLargeForAnIntAsksForEveryOption() {
        String answer = "(99999999999 of) Ohio | Iowa";

        assertEquals(new AcceptedAnswers(2, List.of("Ohio", "Iowa")), AnswerMatchers.acceptedAnswers(answer));
        assertTrue(AnswerMatchers.matches(answer, "Iowa and Ohio", false));
        assertFalse(AnswerMatchers.matches(answer, "Ohio", false));
    }

    @Test
    void matches_responsesThatFitManyOptionsStayFast() {
        // Every response word is a typo of many options, so the ways to cut
        // the response multiply; this used to run out of memory.
        StringBuilder answer = new StringBuilder("(10 of) ");
        for (int i = 0; i < 50; i++) {
            answer.append(i == 0 ? "" : " | ").append("state").append((char) ('a' + i % 26)).append((char) ('a' + i / 26));
        }
        StringBuilder response = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            response.append("statx").append((char) ('a' + i % 26)).append(' ');
        }

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                AnswerMatchers.check(answer.toString(), response.toString(), false));
    }

    @Test
    void matches_rejectsLongResponsesQuickly() {
        String response = "gold ".repeat(500);

        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertFalse(AnswerMatchers.matches("(2 of) gold | frankincense | myrrh", response, false)));
    }

    @Test
    void matches_longListResponsesStayFast() {
        String answer = "(3 of) Alaska | Wyoming | Delaware | North Dakota | South Dakota | Vermont | Montana";
        String response = "Alaska Wyoming Delaware North Dakota South Dakota Vermont Montana Alaska Wyoming Delaware";

        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertTrue(AnswerMatchers.matches(answer, response, false)));
    }

    @Test
    void check_reportsTheWordingThePlayerMatched() {
        assertMatched(List.of("a linen cupboard"), List.of(), "a linen [closet | cupboard]", "linen cupbord");
        assertMatched(List.of("Burma"), List.of(), "[Myanmar | Burma]", "What is Burma?");
        assertMatched(List.of("bare"), List.of(), "bear/bare", "bare");
        // Optional parts stay in the wording, whether or not they were said.
        assertMatched(List.of("(Abraham) Lincoln"), List.of(), "(Abraham) Lincoln", "Lincoln");
    }

    @Test
    void check_reportsTheListOptionsNamed_inTheOrderSaid_andTheOthers() {
        assertMatched(List.of("Uruguay"), List.of("Chile", "Argentina"), "(1 of) Chile | Uruguay | Argentina", "uruguay");
        assertMatched(
                List.of("myrrh", "gold"), List.of("frankincense"),
                "(2 of) gold | frankincense | myrrh", "myrrh and gold"
        );
        assertMatched(
                List.of("coroner", "coronel"), List.of(),
                "(2 of) coroner | [coronet | coronel]", "coroner & coronel"
        );
        // An option named twice is reported once.
        assertMatched(List.of("Burma"), List.of(), "[Myanmar | Burma]", "Burma or Burma");
    }

    @Test
    void check_reportsNothingMatchedForAWrongAnswer() {
        AnswerCheck check = AnswerMatchers.check("Lincoln", "Lincoln", true);

        assertFalse(check.correct());
        assertNull(check.matchedAnswers(), "right words, but wrong without the question form");
        assertNull(check.alsoAccepted());
    }

    @Test
    void acceptedAnswers_givesTheFirstWordingOfEachOption() {
        assertEquals(new AcceptedAnswers(1, List.of("a linen closet")), AnswerMatchers.acceptedAnswers("a linen [closet | cupboard]"));
        assertEquals(
                new AcceptedAnswers(2, List.of("coroner", "coronet")),
                AnswerMatchers.acceptedAnswers("(2 of) coroner | [coronet | coronel]")
        );
        assertEquals(new AcceptedAnswers(1, List.of("bear")), AnswerMatchers.acceptedAnswers("bear/bare"));
        assertNull(AnswerMatchers.acceptedAnswers(null));
    }

    private static void assertFlags(boolean correct, boolean phrasedAsQuestion, AnswerCheck check) {
        assertEquals(correct, check.correct(), "correct");
        assertEquals(phrasedAsQuestion, check.phrasedAsQuestion(), "phrasedAsQuestion");
    }

    private static void assertMatched(List<String> matched, List<String> alsoAccepted, String answer, String response) {
        AnswerCheck check = AnswerMatchers.check(answer, response, false);

        assertTrue(check.correct(), response);
        assertEquals(matched, check.matchedAnswers(), response);
        assertEquals(alsoAccepted, check.alsoAccepted(), response);
    }

    @Test
    void expectsNumber_handlesMissingAnswers() {
        assertFalse(AnswerMatchers.expectsNumber(null));
        assertFalse(AnswerMatchers.expectsNumber("Lincoln"));
    }

    @Test
    void distance_countsASwapAsOneEdit() {
        assertEquals(1, AnswerText.distance("wright", "wrihgt", 2));
        assertEquals(1, AnswerText.distance("lincoln", "lincln", 2));
        assertEquals(3, AnswerText.distance("abc", "xyz", 2), "stops at the limit + 1");
    }
}
