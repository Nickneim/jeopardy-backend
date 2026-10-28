package com.nickneim.jeopardy.session.model;

import com.nickneim.jeopardy.game.entity.ClueType;
import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.dto.ClueTypeResponse;
import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;
import com.nickneim.jeopardy.session.security.PlayerTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The host leaving clue types out of the game in the lobby. */
class GameSessionClueTypesTest {

    private static final BoardClue TEXT = new BoardClue(1L, 1, "Q1", "A1", 200, false);
    private static final BoardClue IMAGE_DAILY_DOUBLE = media(2L, 2, 400, true, ClueType.IMAGE);
    private static final BoardClue SOUND = media(3L, 1, 400, false, ClueType.SOUND);
    private static final BoardClue FINAL_VIDEO = media(4L, 1, 2000, false, ClueType.VIDEO);

    private GameSession session;
    private GameSessionPlayer ana;
    private GameSessionPlayer ben;

    @BeforeEach
    void setUp() {
        session = new GameSession(SessionCode.random(), new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(
                        new BoardCategory("HISTORY", List.of(TEXT, IMAGE_DAILY_DOUBLE))
                )),
                new BoardRound(GameRound.DOUBLE_JEOPARDY, List.of(
                        new BoardCategory("MUSIC", List.of(SOUND))
                )),
                new BoardRound(GameRound.FINAL_JEOPARDY, List.of(
                        new BoardCategory("MOVIES", List.of(FINAL_VIDEO))
                ))
        )));
        ana = session.addPlayer("Ana", PlayerTokens.generate());
        ben = session.addPlayer("Ben", PlayerTokens.generate());
    }

    @Test
    void everyTypeIsEnabledByDefault() {
        assertEquals(Set.of(), session.getDisabledClueTypes());
        assertSame(session.getFullBoard(), session.getBoard());
        assertEquals(
                List.of(
                        new ClueTypeResponse(ClueType.TEXT, 1, true),
                        new ClueTypeResponse(ClueType.IMAGE, 1, true),
                        new ClueTypeResponse(ClueType.SOUND, 1, true),
                        new ClueTypeResponse(ClueType.VIDEO, 1, true)
                ),
                ClueTypeResponse.from(session)
        );
    }

    @Test
    void disabledClues_areLeftOffTheBoard_andTheirDailyDoubleWithThem() {
        session.updateDisabledClueTypes(ana.getId(), Set.of(ClueType.IMAGE));

        assertEquals(Set.of(TEXT.id()), session.getBoard().getClueIds(GameRound.JEOPARDY));
        assertEquals(4, session.getFullBoard().getClueCount());
        assertEquals(3, session.getBoard().getClueCount());
        assertEquals(
                new ClueTypeResponse(ClueType.IMAGE, 1, false),
                ClueTypeResponse.from(session).get(1)
        );

        session.startGame(ana.getId());
        session.finishRoundIntro();

        assertError(GameErrorCode.CLUE_NOT_FOUND, () -> session.chooseClue(ana.getId(), IMAGE_DAILY_DOUBLE.id()));
        session.chooseClue(ana.getId(), TEXT.id());
        assertEquals(GameSessionState.REVEALING_CLUE, session.getState());
    }

    @Test
    void aRoundWithOnlyDisabledClues_isSkipped_andSoIsFinalJeopardy() {
        session.updateDisabledClueTypes(ana.getId(), Set.of(ClueType.IMAGE, ClueType.SOUND, ClueType.VIDEO));

        session.startGame(ana.getId());
        session.finishRoundIntro();
        playRoundWithoutAnswers();

        assertEquals(GameSessionState.FINISHED_GAME, session.getState());
        assertNull(session.getBoard().getFinalJeopardyClue());
    }

    @Test
    void disablingTheFirstRoundsClues_startsTheGameInTheNextRound() {
        GameBoard board = new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(
                        new BoardCategory("ART", List.of(IMAGE_DAILY_DOUBLE))
                )),
                new BoardRound(GameRound.DOUBLE_JEOPARDY, List.of(
                        new BoardCategory("HISTORY", List.of(TEXT))
                ))
        ));
        session = new GameSession(SessionCode.random(), board);
        ana = session.addPlayer("Ana", PlayerTokens.generate());

        session.updateDisabledClueTypes(ana.getId(), Set.of(ClueType.IMAGE));
        assertEquals(GameRound.DOUBLE_JEOPARDY, session.getRound());

        session.updateDisabledClueTypes(ana.getId(), Set.of());
        assertEquals(GameRound.JEOPARDY, session.getRound());
        assertSame(board, session.getBoard());
    }

    @Test
    void enablingATypeAgain_putsItsCluesBack() {
        session.updateDisabledClueTypes(ana.getId(), Set.of(ClueType.IMAGE, ClueType.SOUND));
        session.updateDisabledClueTypes(ana.getId(), Set.of(ClueType.SOUND));

        assertEquals(Set.of(ClueType.SOUND), session.getDisabledClueTypes());
        assertEquals(Set.of(TEXT.id(), IMAGE_DAILY_DOUBLE.id()), session.getBoard().getClueIds(GameRound.JEOPARDY));
        assertTrue(session.getBoard().isDailyDouble(IMAGE_DAILY_DOUBLE.id()));
    }

    @Test
    void textCluesCantBeDisabled() {
        assertError(GameErrorCode.INVALID_REQUEST,
                () -> session.updateDisabledClueTypes(ana.getId(), Set.of(ClueType.TEXT, ClueType.IMAGE)));

        assertEquals(Set.of(), session.getDisabledClueTypes());
        assertEquals(4, session.getBoard().getClueCount());
    }

    @Test
    void disablingEveryClueTheBoardHas_isRefused() {
        session = new GameSession(SessionCode.random(), new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(
                        new BoardCategory("ART", List.of(IMAGE_DAILY_DOUBLE))
                ))
        )));
        ana = session.addPlayer("Ana", PlayerTokens.generate());

        assertError(GameErrorCode.INVALID_REQUEST,
                () -> session.updateDisabledClueTypes(ana.getId(), Set.of(ClueType.IMAGE)));

        assertEquals(Set.of(), session.getDisabledClueTypes());
        assertEquals(1, session.getBoard().getClueCount());
    }

    @Test
    void aTypeTheBoardDoesntHave_canBeDisabled() {
        session = new GameSession(SessionCode.random(), new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(new BoardCategory("HISTORY", List.of(TEXT))))
        )));
        ana = session.addPlayer("Ana", PlayerTokens.generate());

        session.updateDisabledClueTypes(ana.getId(), Set.of(ClueType.VIDEO));

        assertEquals(Set.of(ClueType.VIDEO), session.getDisabledClueTypes());
        // Only the types the game has are offered.
        assertEquals(List.of(new ClueTypeResponse(ClueType.TEXT, 1, true)), ClueTypeResponse.from(session));
    }

    @Test
    void onlyTheHostCanDisableClueTypes() {
        assertError(GameErrorCode.NOT_HOST,
                () -> session.updateDisabledClueTypes(ben.getId(), Set.of(ClueType.IMAGE)));
        assertEquals(Set.of(), session.getDisabledClueTypes());
    }

    @Test
    void clueTypesCantChangeOnceTheGameHasStarted_andArentSentAnyMore() {
        session.startGame(ana.getId());

        assertEquals(List.of(), ClueTypeResponse.from(session));

        assertError(GameErrorCode.INVALID_STATE,
                () -> session.updateDisabledClueTypes(ana.getId(), Set.of(ClueType.IMAGE)));
    }

    private void playRoundWithoutAnswers() {
        while (session.getState() == GameSessionState.CHOOSING_CLUE) {
            session.timeoutChoosingClue();
            session.finishRevealingClue();
            session.timeoutBuzzer();
            session.finishRevealingAnswer();
        }
    }

    private static BoardClue media(Long id, int row, int value, boolean dailyDouble, ClueType type) {
        return new BoardClue(id, row, "Q" + id, "A" + id, value, dailyDouble, type, "https://media.example/" + id);
    }

    private void assertError(GameErrorCode code, Executable action) {
        InvalidGameActionException exception =
                assertThrows(InvalidGameActionException.class, action);

        assertEquals(code, exception.getCode());
    }
}
