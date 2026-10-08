package com.nickneim.jeopardy.session.board;

import com.nickneim.jeopardy.game.entity.GameRound;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GameBoardTest {

    private final BoardClue jeopardyClue =
            new BoardClue(1L, "J question", "J answer", 200, false);
    private final BoardClue jeopardyDailyDouble =
            new BoardClue(2L, "DD question", "DD answer", 1000, true);
    private final BoardClue doubleJeopardyClue =
            new BoardClue(3L, "DJ question", "DJ answer", 800, false);
    private final BoardClue finalClue =
            new BoardClue(4L, "Final question", "Final answer", 2000, false);

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
                new BoardClue(1L, "Other question", "Other answer", 400, false);

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
    void constructor_rejectsBoardWithoutClues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GameBoard(List.of(round(GameRound.JEOPARDY)))
        );
    }

    private BoardRound round(GameRound round, BoardClue... clues) {
        return new BoardRound(
                round,
                List.of(new BoardCategory("CATEGORY", List.of(clues)))
        );
    }
}
