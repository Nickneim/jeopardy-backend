package com.nickneim.jeopardy.session.model;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;
import com.nickneim.jeopardy.session.exception.LateJoinNotAllowedException;
import com.nickneim.jeopardy.session.security.PlayerTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Joining after the start, and the score new players start with. */
class GameSessionJoiningTest {

    // Row n is worth 200 * n.
    private static final List<BoardClue> HISTORY = clues(100);
    private static final List<BoardClue> SCIENCE = clues(200);

    private GameSession session;
    private GameSessionPlayer ana;
    private GameSessionPlayer ben;

    @BeforeEach
    void setUp() {
        session = new GameSession(SessionCode.random(), new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(
                        new BoardCategory("HISTORY", HISTORY),
                        new BoardCategory("SCIENCE", SCIENCE)
                ))
        )));
        ana = session.addPlayer("Ana", PlayerTokens.generate());
        ben = session.addPlayer("Ben", PlayerTokens.generate());
    }

    // -------------------------------------------------------------------------
    // Join score
    // -------------------------------------------------------------------------

    @Test
    void joinScore_startsAtZero() {
        assertEquals(0, session.getJoinScore());
        assertEquals(0, ana.getScore());
    }

    @Test
    void scoreBelowTheJoinScore_lowersIt_andNewPlayersStartThere() {
        session.startGame(ana.getId());
        play(HISTORY.get(0), ben, false);

        assertEquals(-200, session.getJoinScore());
        assertEquals(-200, session.addPlayer("Cy", PlayerTokens.generate()).getScore());
    }

    @Test
    void lowestPlayerLeavingAndRejoining_keepsTheirLowScore() {
        session.startGame(ana.getId());
        play(HISTORY.get(1), ben, false);

        session.leave(ben.getId());

        assertEquals(List.of(ana), session.getPlayers());
        assertEquals(-400, session.getJoinScore());
        assertEquals(-400, session.addPlayer("Ben", PlayerTokens.generate()).getScore());
    }

    @Test
    void bottomPlayerPushedUp_raisesTheJoinScoreToTheLowestScore() {
        session.startGame(ana.getId());
        play(HISTORY.get(0), ana, false);
        play(HISTORY.get(1), ben, false);
        assertEquals(-400, session.getJoinScore());

        // Ben (-400) answers right and is no longer at the bottom; Ana (-200) is.
        play(SCIENCE.get(0), ben, true);

        assertEquals(-200, ben.getScore());
        assertEquals(-200, ana.getScore());
        assertEquals(-200, session.getJoinScore());
    }

    @Test
    void joinScore_neverRisesAboveZero() {
        session.startGame(ana.getId());
        play(HISTORY.get(0), ana, true);
        play(HISTORY.get(1), ben, false);

        play(SCIENCE.get(4), ben, true);

        assertEquals(200, ana.getScore());
        assertEquals(600, ben.getScore());
        assertEquals(0, session.getJoinScore());
    }

    @Test
    void joinScore_staysWhileAnotherPlayerIsStillAtTheBottom() {
        GameSessionPlayer cy = session.addPlayer("Cy", PlayerTokens.generate());
        session.startGame(ana.getId());
        play(HISTORY.get(0), ana, false);
        play(HISTORY.get(1), ben, false);
        play(HISTORY.get(2), cy, false);
        play(SCIENCE.get(3), ben, true);
        assertEquals(-600, session.getJoinScore());

        // Ben scored but Cy is still at -600.
        assertEquals(400, ben.getScore());
        assertEquals(-600, cy.getScore());

        play(SCIENCE.get(4), cy, true);

        assertEquals(-200, session.getJoinScore());
    }

    @Test
    void playerAboveTheJoinScore_scoringDoesntChangeIt() {
        session.startGame(ana.getId());
        play(HISTORY.get(0), ben, false);

        play(HISTORY.get(1), ana, true);
        play(HISTORY.get(2), ana, false);

        assertEquals(-200, ana.getScore());
        assertEquals(-200, session.getJoinScore());
    }

    @Test
    void joinScore_staysAfterTheBottomPlayerLeaves_untilAJoinerAtItIsPushedUp() {
        session.startGame(ana.getId());
        play(HISTORY.get(1), ben, false);
        session.leave(ben.getId());

        // Ana (0) scoring doesn't count: she isn't at the join score.
        play(HISTORY.get(0), ana, true);
        assertEquals(-400, session.getJoinScore());

        GameSessionPlayer dee = session.addPlayer("Dee", PlayerTokens.generate());
        play(SCIENCE.get(4), dee, true);

        assertEquals(600, dee.getScore());
        assertEquals(0, session.getJoinScore());
    }

    @Test
    void dailyDoubleLoss_canLowerTheJoinScore() {
        BoardClue dailyDouble = new BoardClue(301L, 5, "DD question", "DD answer", 1000, true);
        session = new GameSession(SessionCode.random(), new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(
                        new BoardCategory("HISTORY", List.of(HISTORY.get(0), dailyDouble))
                ))
        )));
        ana = session.addPlayer("Ana", PlayerTokens.generate());
        session.startGame(ana.getId());

        session.chooseClue(ana.getId(), dailyDouble.id());
        session.placeDailyDoubleWager(ana.getId(), 700);
        session.finishRevealingClue();
        session.answerClue(ana.getId(), "Wrong");

        assertEquals(-700, session.getJoinScore());
    }

    // -------------------------------------------------------------------------
    // Late joining
    // -------------------------------------------------------------------------

    @Test
    void playersCanJoinAfterTheStartByDefault() {
        session.startGame(ana.getId());

        assertTrue(session.isLateJoinAllowed());
        assertTrue(session.isJoinable());
        assertEquals(3L, session.addPlayer("Cy", PlayerTokens.generate()).getId());
    }

    @Test
    void hostCanBlockJoiningAfterTheStart() {
        session.updateLateJoin(ana.getId(), false);

        // The lobby is still open.
        GameSessionPlayer cy = session.addPlayer("Cy", PlayerTokens.generate());
        assertTrue(session.getPlayers().contains(cy));

        session.startGame(ana.getId());

        assertFalse(session.isJoinable());
        assertThrows(LateJoinNotAllowedException.class, () -> session.addPlayer("Dee", PlayerTokens.generate()));
        assertEquals(3, session.getPlayers().size());
    }

    @Test
    void blockedJoining_alsoKeepsOutPlayersWhoLeft() {
        session.updateLateJoin(ana.getId(), false);
        session.startGame(ana.getId());
        session.leave(ben.getId());

        assertThrows(LateJoinNotAllowedException.class, () -> session.addPlayer("Ben", PlayerTokens.generate()));
    }

    @Test
    void blockedJoining_appliesToAFinishedGame() {
        session.updateLateJoin(ana.getId(), false);
        session.startGame(ana.getId());
        for (int i = 0; i < 10; i++) {
            session.timeoutChoosingClue();
            session.finishRevealingClue();
            session.timeoutBuzzer();
            session.finishRevealingAnswer();
        }
        assertEquals(GameSessionState.FINISHED_GAME, session.getState());

        assertThrows(LateJoinNotAllowedException.class, () -> session.addPlayer("Dee", PlayerTokens.generate()));
    }

    @Test
    void hostCanAllowJoiningAgainBeforeTheStart() {
        session.updateLateJoin(ana.getId(), false);
        session.updateLateJoin(ana.getId(), true);
        session.startGame(ana.getId());

        assertTrue(session.isJoinable());
    }

    @Test
    void onlyTheHostCanChangeLateJoining() {
        assertError(GameErrorCode.NOT_HOST, () -> session.updateLateJoin(ben.getId(), false));
        assertTrue(session.isLateJoinAllowed());
    }

    @Test
    void lateJoiningCantChangeOnceTheGameHasStarted() {
        session.startGame(ana.getId());

        assertError(GameErrorCode.INVALID_STATE, () -> session.updateLateJoin(ana.getId(), false));
    }

    @Test
    void closedSession_isNotJoinable() {
        session.close(SessionCloseReason.IDLE);

        assertFalse(session.isJoinable());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static List<BoardClue> clues(long firstId) {
        return List.of(1, 2, 3, 4, 5).stream()
                .map(row -> new BoardClue(firstId + row, row, "Question " + firstId + row, "Answer " + (firstId + row), 200 * row, false))
                .toList();
    }

    // The current chooser picks the clue; the buzzer answers it right or wrong.
    private void play(BoardClue clue, GameSessionPlayer buzzer, boolean correct) {
        session.chooseClue(session.getPlayerChoosingClue(), clue.id());
        session.finishRevealingClue();
        session.buzz(buzzer.getId());
        session.answerClue(buzzer.getId(), correct ? clue.answer() : "Wrong");

        if (session.getState() == GameSessionState.WAITING_FOR_BUZZER) {
            session.timeoutBuzzer();
        }

        session.finishRevealingAnswer();
    }

    private void assertError(GameErrorCode code, Executable action) {
        InvalidGameActionException exception =
                assertThrows(InvalidGameActionException.class, action);

        assertEquals(code, exception.getCode());
    }
}
