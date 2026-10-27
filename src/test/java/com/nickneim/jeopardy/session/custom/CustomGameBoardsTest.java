package com.nickneim.jeopardy.session.custom;

import com.nickneim.jeopardy.exception.InvalidParameterException;
import com.nickneim.jeopardy.game.entity.ClueType;
import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.dto.CustomCategoryRequest;
import com.nickneim.jeopardy.session.dto.CustomClueRequest;
import com.nickneim.jeopardy.session.dto.CustomFinalJeopardyRequest;
import com.nickneim.jeopardy.session.dto.CustomGameRequest;
import com.nickneim.jeopardy.session.exception.InvalidCustomGameException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.nickneim.jeopardy.session.custom.CustomGameProblemCode.*;
import static org.junit.jupiter.api.Assertions.*;

class CustomGameBoardsTest {

    private static final GameRound J = GameRound.JEOPARDY;
    private static final GameRound DJ = GameRound.DOUBLE_JEOPARDY;
    private static final GameRound FJ = GameRound.FINAL_JEOPARDY;

    // ---------- building the board ----------

    @Test
    void buildsTheBoard_withIdsInBoardOrder_andValuesFromTheRows() {
        GameBoard board = CustomGameBoards.toBoard(show(
                List.of(
                        category(2, "Second", clue(1)),
                        category(1, "First", clue(5), clue(3))
                ),
                List.of(category(4, "Doubled", clue(2))),
                new CustomFinalJeopardyRequest(" Pets ", "(Alex: furry ones)", "Final question", "a cat")
        ));

        assertTrue(board.isCustom());
        assertNull(board.getGameId());
        assertNull(board.getSeason());
        assertNull(board.getGameDate());
        assertEquals("Family reunion night", board.getTitle());
        assertEquals(5, board.getClueCount());

        BoardRound jeopardy = board.getRound(J).orElseThrow();
        assertEquals(List.of("First", "Second"), jeopardy.categories().stream().map(BoardCategory::name).toList());
        assertEquals(
                List.of(new BoardClue(1L, 3, "Question 3", "Answer 3", 600, false),
                        new BoardClue(2L, 5, "Question 5", "Answer 5", 1000, false)),
                jeopardy.categories().getFirst().clues()
        );
        assertEquals(new BoardClue(3L, 1, "Question 1", "Answer 1", 200, false), board.getClue(3L));
        assertEquals(new BoardClue(4L, 2, "Question 2", "Answer 2", 800, false), board.getClue(4L));
        assertEquals(new BoardClue(5L, 1, "Final question", "a cat", 2000, false), board.getFinalJeopardyClue());
        assertEquals(new BoardCategory("Pets", "(Alex: furry ones)", List.of(board.getFinalJeopardyClue())),
                board.getFinalJeopardyCategory());
    }

    @Test
    void keepsQuestionsExactlyAsWritten_tagsIncluded() {
        String question = "  This <i>dog</i><br>barks &amp; bites  ";
        GameBoard board = CustomGameBoards.toBoard(oneClue(
                new CustomClueRequest(1, question, "a dog", ClueType.TEXT, null, false)
        ));

        assertEquals(question, board.getClue(1L).question());
    }

    @Test
    void keepsTheCommentsAndMediaOfEachClue() {
        GameBoard board = CustomGameBoards.toBoard(show(
                List.of(new CustomCategoryRequest(1, "Pets", "(Alex: all real)", 7L, List.of(
                        new CustomClueRequest(1, "Q", "a dog", ClueType.IMAGE, "https://example.org/dog.png", false)
                ))),
                List.of(),
                null
        ));

        BoardCategory category = board.getRound(J).orElseThrow().categories().getFirst();
        assertEquals("(Alex: all real)", category.comments());
        assertEquals(ClueType.IMAGE, category.clues().getFirst().clueType());
        assertEquals("https://example.org/dog.png", category.clues().getFirst().mediaUrl());
    }

    @Test
    void hasExactlyThePinnedDailyDoubles() {
        GameBoard board = CustomGameBoards.toBoard(show(
                List.of(category(1, "A", clue(1), dailyDouble(2))),
                List.of(category(1, "B", dailyDouble(1), dailyDouble(2), clue(3))),
                null
        ));

        assertEquals(Set.of(2L, 3L, 4L), Stream.of(1L, 2L, 3L, 4L, 5L)
                .filter(board::isDailyDouble)
                .collect(Collectors.toSet()));
    }

    @Test
    void aShowWithoutDailyDoubles_getsNone() {
        GameBoard board = CustomGameBoards.toBoard(show(
                List.of(category(1, "A", clue(1), clue(2))),
                List.of(category(1, "B", clue(1))),
                null
        ));

        assertFalse(board.isDailyDouble(1L) || board.isDailyDouble(2L) || board.isDailyDouble(3L));
    }

    @Test
    void namedCategoriesWithoutClues_stayOnTheBoard_unnamedOnesDont() {
        GameBoard board = CustomGameBoards.toBoard(show(
                List.of(category(1, "Has clues", clue(1)), category(2, "Empty"), category(3, null), category(4, "  ")),
                List.of(),
                null
        ));

        assertEquals(
                List.of("Has clues", "Empty"),
                board.getRound(J).orElseThrow().categories().stream().map(BoardCategory::name).toList()
        );
    }

    @Test
    void rejectsTwoCategoriesInOneColumn_asABadRequest() {
        InvalidParameterException exception = assertThrows(InvalidParameterException.class, () -> CustomGameBoards.toBoard(show(
                List.of(),
                List.of(category(3, "A", clue(1)), category(3, "B", clue(1))),
                null
        )));

        assertEquals("customGame.doubleJeopardyRound has two categories in column 3", exception.getMessage());
    }

    @Test
    void rejectsTwoCluesOnOneRow_asABadRequest() {
        InvalidParameterException exception = assertThrows(InvalidParameterException.class, () -> CustomGameBoards.toBoard(show(
                List.of(category(1, "A", clue(1)), category(2, "B", clue(4), clue(4))),
                List.of(),
                null
        )));

        assertEquals("customGame.jeopardyRound[1].clues has two clues on row 4", exception.getMessage());
    }

    // ---------- rule 1: at least one clue ----------

    @Test
    void rule1_oneClueInAnyRoundIsEnough() {
        assertDoesNotThrow(() -> CustomGameBoards.toBoard(show(List.of(), List.of(category(6, "A", clue(5))), null)));
        assertDoesNotThrow(() -> CustomGameBoards.toBoard(show(List.of(), List.of(), finalJeopardy())));
    }

    @Test
    void rule1_noClues() {
        assertEquals(
                List.of(new CustomGameProblem(NO_CLUES, null, null, null, "The show has no clues")),
                problems(show(List.of(category(1, "Named but empty")), List.of(), null))
        );
    }

    @Test
    void rule1_aFinalJeopardyCategoryWithoutAClueIsNoClue() {
        assertEquals(
                List.of(NO_CLUES, FINAL_CLUE_MISSING),
                codes(show(List.of(), List.of(), new CustomFinalJeopardyRequest("Pets", null, null, null)))
        );
    }

    // ---------- rule 2: the show's name ----------

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    void rule2_showNameMissing(String name) {
        assertEquals(
                List.of(new CustomGameProblem(SHOW_NAME_MISSING, null, null, null, "The show has no name")),
                problems(named(name))
        );
    }

    @Test
    void rule2_nullShowNameIsMissing() {
        assertEquals(List.of(SHOW_NAME_MISSING), codes(named(null)));
    }

    @Test
    void rule2_showNameTooLong() {
        assertEquals(
                List.of(new CustomGameProblem(SHOW_NAME_TOO_LONG, null, null, null,
                        "The show's name is 101 characters long; the most is 100")),
                problems(named("a".repeat(101)))
        );
    }

    @Test
    void rule2_namesAreCountedInCodePoints_afterTrimming() {
        GameBoard board = CustomGameBoards.toBoard(named("  " + "😀".repeat(100) + "  "));

        assertEquals("😀".repeat(100), board.getTitle());
    }

    // ---------- rule 3: category names ----------

    @Test
    void rule3_categoryNameMissing() {
        assertEquals(
                List.of(new CustomGameProblem(CATEGORY_NAME_MISSING, DJ, 3, null, "Category 3 has clues but no name")),
                problems(show(List.of(category(1, "A", clue(1))), List.of(category(3, " ", clue(2))), null))
        );
    }

    @Test
    void rule3_categoryNameTooLong_evenWithoutClues() {
        assertEquals(
                List.of(new CustomGameProblem(CATEGORY_NAME_TOO_LONG, J, 2, null,
                        "Category 2's name is 101 characters long; the most is 100")),
                problems(show(List.of(category(1, "A", clue(1)), category(2, "b".repeat(101))), List.of(), null))
        );
    }

    @Test
    void rule3_aCategoryNameOf100CharactersIsFine() {
        assertDoesNotThrow(() -> CustomGameBoards.toBoard(show(List.of(category(1, "b".repeat(100), clue(1))), List.of(), null)));
    }

    // ---------- rule 4: Final Jeopardy is all or nothing ----------

    @Test
    void rule4_noFinalJeopardy_orAnEmptyOne_isFine() {
        GameBoard withoutFinal = CustomGameBoards.toBoard(oneClue(clue(1)));
        GameBoard emptyFinal = CustomGameBoards.toBoard(show(
                List.of(category(1, "A", clue(1))), List.of(), new CustomFinalJeopardyRequest(" ", "comments", "", null)
        ));

        assertNull(withoutFinal.getFinalJeopardyClue());
        assertNull(emptyFinal.getFinalJeopardyClue());
        assertTrue(emptyFinal.getRound(FJ).isEmpty());
    }

    @Test
    void rule4_finalClueMissing() {
        assertEquals(
                List.of(new CustomGameProblem(FINAL_CLUE_MISSING, FJ, null, null, "Final Jeopardy has a category but no clue")),
                problems(withFinal(new CustomFinalJeopardyRequest("Pets", null, " ", null)))
        );
    }

    @Test
    void rule4_aFinalClueWithoutACategory() {
        assertEquals(
                List.of(new CustomGameProblem(CATEGORY_NAME_MISSING, FJ, null, null, "Final Jeopardy has a clue but no category")),
                problems(withFinal(new CustomFinalJeopardyRequest(null, null, "Final question", "a cat")))
        );
    }

    @Test
    void rule4_finalCategoryNameTooLong() {
        assertEquals(
                List.of(new CustomGameProblem(CATEGORY_NAME_TOO_LONG, FJ, null, null,
                        "The Final Jeopardy category's name is 101 characters long; the most is 100")),
                problems(withFinal(new CustomFinalJeopardyRequest("p".repeat(101), null, "Final question", "a cat")))
        );
    }

    @Test
    void rule4_aFinalClueNeedsBothQuestionAndAnswer() {
        assertEquals(
                List.of(new CustomGameProblem(QUESTION_MISSING, FJ, null, null, "The Final Jeopardy clue has no text")),
                problems(withFinal(new CustomFinalJeopardyRequest("Pets", null, null, "a cat")))
        );
        assertEquals(
                List.of(new CustomGameProblem(ANSWER_MISSING, FJ, null, null, "The Final Jeopardy clue has no correct response")),
                problems(withFinal(new CustomFinalJeopardyRequest("Pets", null, "Final question", "")))
        );
    }

    @Test
    void rule4_theFinalClueIsCheckedLikeAnyOther() {
        assertEquals(
                List.of(
                        new CustomGameProblem(QUESTION_TOO_LONG, FJ, null, null,
                                "The Final Jeopardy clue's text is 1001 characters long; the most is 1000"),
                        new CustomGameProblem(ANSWER_UNREADABLE, FJ, null, null,
                                "The correct response can't be read: a ) has no ( before it")
                ),
                problems(withFinal(new CustomFinalJeopardyRequest("Pets", null, "q".repeat(1001), "a cat)")))
        );
    }

    // ---------- rule 5: question and answer ----------

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void rule5_questionMissing(String question) {
        assertEquals(
                List.of(new CustomGameProblem(QUESTION_MISSING, J, 1, 2, "This clue has no text")),
                problems(oneClue(new CustomClueRequest(2, question, "a dog", ClueType.TEXT, null, false)))
        );
        assertEquals(List.of(QUESTION_MISSING), codes(oneClue(new CustomClueRequest(2, null, "a dog", ClueType.TEXT, null, false))));
    }

    @Test
    void rule5_questionTooLong() {
        assertEquals(
                List.of(new CustomGameProblem(QUESTION_TOO_LONG, J, 1, 1,
                        "This clue's text is 1001 characters long; the most is 1000")),
                problems(oneClue(new CustomClueRequest(1, "q".repeat(1001), "a dog", ClueType.TEXT, null, false)))
        );
    }

    @Test
    void rule5_aQuestionOf1000CharactersIsFine_countingCodePoints() {
        assertDoesNotThrow(() -> CustomGameBoards.toBoard(oneClue(
                new CustomClueRequest(1, "😀".repeat(1000), "a dog", ClueType.TEXT, null, false)
        )));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void rule5_answerMissing(String answer) {
        assertEquals(
                List.of(new CustomGameProblem(ANSWER_MISSING, J, 1, 3, "This clue has no correct response")),
                problems(oneClue(new CustomClueRequest(3, "Q", answer, ClueType.TEXT, null, false)))
        );
        assertEquals(List.of(ANSWER_MISSING), codes(oneClue(new CustomClueRequest(3, "Q", null, ClueType.TEXT, null, false))));
    }

    @Test
    void rule5_answerTooLong() {
        assertEquals(
                List.of(new CustomGameProblem(ANSWER_TOO_LONG, J, 1, 1,
                        "The correct response is 501 characters long; the most is 500")),
                problems(oneClue(new CustomClueRequest(1, "Q", "a".repeat(501), ClueType.TEXT, null, false)))
        );
        assertDoesNotThrow(() -> CustomGameBoards.toBoard(oneClue(
                new CustomClueRequest(1, "Q", "a".repeat(500), ClueType.TEXT, null, false)
        )));
    }

    // ---------- rule 6: the answer can be read ----------

    @Test
    void rule6_answerUnreadable_saysWhy() {
        assertEquals(
                List.of(new CustomGameProblem(ANSWER_UNREADABLE, DJ, 2, 3,
                        "The correct response can't be read: a [ is never closed")),
                problems(show(List.of(), List.of(category(2, "A",
                        new CustomClueRequest(3, "Q", "a linen [closet | cupboard", ClueType.TEXT, null, false)
                )), null))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "(Abraham) Lincoln",
            "[Myanmar | Burma]",
            "(2 of) gold | frankincense | myrrh",
            "(2 of) coroner | [coronet | coronel]",
            "bear/bare",
            "2/3",
            "<i>Hamlet</i>"
    })
    void rule6_answersInTheStoredFormatAreFine(String answer) {
        assertDoesNotThrow(() -> CustomGameBoards.toBoard(oneClue(
                new CustomClueRequest(1, "Q", answer, ClueType.TEXT, null, false)
        )));
    }

    // ---------- rule 7: media matches the type ----------

    @ParameterizedTest
    @ValueSource(strings = {"IMAGE", "SOUND", "VIDEO"})
    void rule7_mediaUrlMissing(ClueType type) {
        String kind = type.name().toLowerCase();

        assertEquals(
                List.of(new CustomGameProblem(MEDIA_URL_MISSING, J, 1, 1, "This " + kind + " clue has no media link")),
                problems(oneClue(new CustomClueRequest(1, "Q", "a dog", type, null, false)))
        );
        assertEquals(List.of(MEDIA_URL_MISSING), codes(oneClue(new CustomClueRequest(1, "Q", "a dog", type, " ", false))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.org/dog.png", "example.org/dog.png", "https://", "javascript:alert(1)", "https://exa mple.org/x"})
    void rule7_mediaUrlInvalid(String url) {
        assertEquals(
                List.of(new CustomGameProblem(MEDIA_URL_INVALID, J, 1, 1, "This sound clue's media link isn't an http or https URL")),
                problems(oneClue(new CustomClueRequest(1, "Q", "a dog", ClueType.SOUND, url, false)))
        );
    }

    @Test
    void rule7_mediaUrlTooLong() {
        // "https://example.org/" is 20 characters.
        String longest = "https://example.org/" + "x".repeat(1980);

        assertEquals(
                List.of(new CustomGameProblem(MEDIA_URL_INVALID, J, 1, 1,
                        "This video clue's media link is 2001 characters long; the most is 2000")),
                problems(oneClue(new CustomClueRequest(1, "Q", "a dog", ClueType.VIDEO, longest + "x", false)))
        );
        assertDoesNotThrow(() -> CustomGameBoards.toBoard(oneClue(
                new CustomClueRequest(1, "Q", "a dog", ClueType.VIDEO, longest, false)
        )));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.org/dog.png", "http://192.168.0.7:8080/a?b=c#d", "HTTPS://cdn.anywhere.example/x.mp4"})
    void rule7_anyHttpOrHttpsUrlOnAnyHostIsFine(String url) {
        GameBoard board = CustomGameBoards.toBoard(oneClue(new CustomClueRequest(1, "Q", "a dog", ClueType.IMAGE, url, false)));

        assertEquals(url, board.getClue(1L).mediaUrl());
    }

    @Test
    void rule7_mediaUrlNotAllowed() {
        assertEquals(
                List.of(new CustomGameProblem(MEDIA_URL_NOT_ALLOWED, J, 1, 1,
                        "This text clue has a media link; only image, sound and video clues can have one")),
                problems(oneClue(new CustomClueRequest(1, "Q", "a dog", ClueType.TEXT, "https://example.org/dog.png", false)))
        );
    }

    // ---------- rule 8: Daily Doubles ----------

    @Test
    void rule8_tooManyDailyDoublesInJeopardy() {
        assertEquals(
                List.of(new CustomGameProblem(TOO_MANY_DAILY_DOUBLES, J, null, null,
                        "Jeopardy has 2 Daily Doubles; it can have at most 1")),
                problems(show(List.of(category(1, "A", dailyDouble(1)), category(2, "B", dailyDouble(1))), List.of(), null))
        );
    }

    @Test
    void rule8_tooManyDailyDoublesInDoubleJeopardy() {
        assertEquals(
                List.of(new CustomGameProblem(TOO_MANY_DAILY_DOUBLES, DJ, null, null,
                        "Double Jeopardy has 3 Daily Doubles; it can have at most 2")),
                problems(show(List.of(), List.of(category(1, "A", dailyDouble(1), dailyDouble(2), dailyDouble(3))), null))
        );
    }

    // ---------- every problem at once ----------

    @Test
    void reportsEveryProblem_showFirst_thenRoundByRound_byColumnAndRow() {
        CustomGameRequest request = new CustomGameRequest(
                " ",
                List.of(
                        category(4, "D", new CustomClueRequest(2, "Q", "a dog", ClueType.VIDEO, null, false)),
                        category(3, null, clue(1))
                ),
                List.of(category(2, "B", new CustomClueRequest(3, "Q", "(1 of) x |", ClueType.TEXT, null, false))),
                new CustomFinalJeopardyRequest("Pets", null, null, null)
        );

        InvalidCustomGameException exception = assertThrows(InvalidCustomGameException.class, () -> CustomGameBoards.toBoard(request));

        assertEquals("This custom show can't be played: 5 problems", exception.getMessage());
        assertEquals(
                List.of(
                        new CustomGameProblem(SHOW_NAME_MISSING, null, null, null, "The show has no name"),
                        new CustomGameProblem(CATEGORY_NAME_MISSING, J, 3, null, "Category 3 has clues but no name"),
                        new CustomGameProblem(MEDIA_URL_MISSING, J, 4, 2, "This video clue has no media link"),
                        new CustomGameProblem(ANSWER_UNREADABLE, DJ, 2, 3,
                                "The correct response can't be read: the list has an empty option"),
                        new CustomGameProblem(FINAL_CLUE_MISSING, FJ, null, null, "Final Jeopardy has a category but no clue")
                ),
                exception.getProblems()
        );
    }

    @Test
    void oneProblem_isSingular() {
        InvalidCustomGameException exception = assertThrows(InvalidCustomGameException.class, () -> CustomGameBoards.toBoard(named(null)));

        assertEquals("This custom show can't be played: 1 problem", exception.getMessage());
    }

    // ---------- helpers ----------

    private static CustomClueRequest clue(int row) {
        return new CustomClueRequest(row, "Question " + row, "Answer " + row, ClueType.TEXT, null, false);
    }

    private static CustomClueRequest dailyDouble(int row) {
        return new CustomClueRequest(row, "Question " + row, "Answer " + row, ClueType.TEXT, null, true);
    }

    private static CustomCategoryRequest category(int column, String name, CustomClueRequest... clues) {
        return new CustomCategoryRequest(column, name, null, null, List.of(clues));
    }

    private static CustomFinalJeopardyRequest finalJeopardy() {
        return new CustomFinalJeopardyRequest("Pets", null, "Final question", "a cat");
    }

    private static CustomGameRequest show(
            List<CustomCategoryRequest> jeopardy,
            List<CustomCategoryRequest> doubleJeopardy,
            CustomFinalJeopardyRequest finalJeopardy
    ) {
        return new CustomGameRequest("Family reunion night", jeopardy, doubleJeopardy, finalJeopardy);
    }

    private static CustomGameRequest oneClue(CustomClueRequest clue) {
        return show(List.of(category(1, "Pets", clue)), List.of(), null);
    }

    private static CustomGameRequest named(String name) {
        return new CustomGameRequest(name, List.of(category(1, "Pets", clue(1))), List.of(), null);
    }

    private static CustomGameRequest withFinal(CustomFinalJeopardyRequest finalJeopardy) {
        return show(List.of(category(1, "A", clue(1))), List.of(), finalJeopardy);
    }

    private static List<CustomGameProblem> problems(CustomGameRequest request) {
        return assertThrows(InvalidCustomGameException.class, () -> CustomGameBoards.toBoard(request)).getProblems();
    }

    private static List<CustomGameProblemCode> codes(CustomGameRequest request) {
        return problems(request).stream().map(CustomGameProblem::code).toList();
    }
}
