package com.nickneim.jeopardy.session.board;

import com.nickneim.jeopardy.game.entity.GameRound;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GameBoardTest {

    private final BoardClue jeopardyClue =
            new BoardClue(1L, 1, "J question", "J answer", 200, false);
    private final BoardClue jeopardyDailyDouble =
            new BoardClue(2L, 5, "DD question", "DD answer", 1000, true);
    private final BoardClue doubleJeopardyClue =
            new BoardClue(3L, 2, "DJ question", "DJ answer", 800, false);
    private final BoardClue finalClue =
            new BoardClue(4L, 1, "Final question", "Final answer", 2000, false);

    private final GameBoard board = new GameBoard(List.of(
            round(GameRound.FINAL_JEOPARDY, finalClue),
            round(GameRound.JEOPARDY, jeopardyClue, jeopardyDailyDouble),
            round(GameRound.DOUBLE_JEOPARDY, doubleJeopardyClue)
    ));

    @Test
    void getRounds_areInPlayOrder() {
        assertEquals(
                List.of(GameRound.JEOPARDY, GameRound.DOUBLE_JEOPARDY, GameRound.FINAL_JEOPARDY),
                board.getRounds().stream().map(BoardRound::round).toList()
        );
    }

    @Test
    void getClue_returnsClueById() {
        assertEquals(doubleJeopardyClue, board.getClue(3L));
    }

    @Test
    void getClue_rejectsUnknownClue() {
        assertThrows(
                IllegalArgumentException.class,
                () -> board.getClue(999L)
        );
    }

    @Test
    void isDailyDouble_isTrueOnlyForDailyDoubleClues() {
        assertTrue(board.isDailyDouble(2L));
        assertFalse(board.isDailyDouble(1L));
        assertFalse(board.isDailyDouble(999L));
    }

    @Test
    void getClueIds_returnsCluesOfThatRound() {
        assertEquals(Set.of(1L, 2L), board.getClueIds(GameRound.JEOPARDY));
        assertEquals(Set.of(4L), board.getClueIds(GameRound.FINAL_JEOPARDY));
    }

    @Test
    void getHighestValue_returnsHighestClueValueInRound() {
        assertEquals(1000, board.getHighestValue(GameRound.JEOPARDY));
        assertEquals(800, board.getHighestValue(GameRound.DOUBLE_JEOPARDY));
    }

    @Test
    void getFirstRound_andNextRound_followPlayOrder() {
        assertEquals(GameRound.JEOPARDY, board.getFirstRound());
        assertEquals(Optional.of(GameRound.DOUBLE_JEOPARDY), board.getNextRound(GameRound.JEOPARDY));
        assertEquals(Optional.of(GameRound.FINAL_JEOPARDY), board.getNextRound(GameRound.DOUBLE_JEOPARDY));
        assertEquals(Optional.empty(), board.getNextRound(GameRound.FINAL_JEOPARDY));
    }

    @Test
    void getNextRound_skipsMissingAndEmptyRounds() {
        GameBoard noDoubleJeopardy = new GameBoard(List.of(
                round(GameRound.JEOPARDY, jeopardyClue),
                round(GameRound.DOUBLE_JEOPARDY),
                round(GameRound.FINAL_JEOPARDY, finalClue)
        ));

        assertEquals(
                Optional.of(GameRound.FINAL_JEOPARDY),
                noDoubleJeopardy.getNextRound(GameRound.JEOPARDY)
        );
    }

    @Test
    void getFirstRound_skipsEmptyRounds() {
        GameBoard onlyFinal = new GameBoard(List.of(
                round(GameRound.JEOPARDY),
                round(GameRound.FINAL_JEOPARDY, finalClue)
        ));

        assertEquals(GameRound.FINAL_JEOPARDY, onlyFinal.getFirstRound());
    }

    @Test
    void constructor_rejectsDuplicateClueIds() {
        BoardClue duplicate =
                new BoardClue(1L, 1, "Other question", "Other answer", 400, false);

        assertThrows(
                IllegalArgumentException.class,
                () -> new GameBoard(List.of(
                        round(GameRound.JEOPARDY, jeopardyClue),
                        round(GameRound.DOUBLE_JEOPARDY, duplicate)
                ))
        );
    }

    @Test
    void constructor_rejectsDuplicateRounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GameBoard(List.of(
                        round(GameRound.JEOPARDY, jeopardyClue),
                        round(GameRound.JEOPARDY, jeopardyDailyDouble)
                ))
        );
    }

    @Test
    void constructor_rejectsFinalJeopardyWithMoreThanOneClue() {
        BoardClue secondFinal =
                new BoardClue(5L, 2, "Another final", "Another answer", 2000, false);

        assertThrows(
                IllegalArgumentException.class,
                () -> new GameBoard(List.of(
                        round(GameRound.FINAL_JEOPARDY, finalClue, secondFinal)
                ))
        );
    }

    @Test
    void constructor_rejectsBoardWithoutClues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GameBoard(List.of(round(GameRound.JEOPARDY)))
        );
    }

    @Test
    void category_sortsCluesByRowAndAllowsGaps() {
        BoardClue rowFour = new BoardClue(5L, 4, "Q4", "A4", 800, false);
        BoardClue rowOne = new BoardClue(6L, 1, "Q1", "A1", 200, false);

        BoardCategory category =
                new BoardCategory("GAPS", List.of(rowFour, rowOne));

        assertEquals(List.of(rowOne, rowFour), category.clues());
    }

    @Test
    void category_rejectsTwoCluesOnTheSameRow() {
        BoardClue first = new BoardClue(5L, 2, "Q", "A", 400, false);
        BoardClue second = new BoardClue(6L, 2, "Q", "A", 400, false);

        assertThrows(
                IllegalArgumentException.class,
                () -> new BoardCategory("CLASH", List.of(first, second))
        );
    }

    @Test
    void clue_rejectsRowBelowOne() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BoardClue(5L, 0, "Q", "A", 200, false)
        );
    }

    private BoardRound round(GameRound round, BoardClue... clues) {
        return new BoardRound(
                round,
                List.of(new BoardCategory("CATEGORY", List.of(clues)))
        );
    }
}
