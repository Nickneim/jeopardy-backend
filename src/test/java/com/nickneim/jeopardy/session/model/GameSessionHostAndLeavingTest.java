package com.nickneim.jeopardy.session.model;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The host role, kicking, and the rules for players who leave. */
class GameSessionHostAndLeavingTest {

    private static final BoardClue HISTORY_200 =
            new BoardClue(101L, 1, "History question", "History answer", 200, false);
    private static final BoardClue HISTORY_400 =
            new BoardClue(102L, 2, "Second history question", "Second history answer", 400, false);
    private static final BoardClue SCIENCE_200 =
            new BoardClue(103L, 1, "Science question", "Science answer", 200, false);
    private static final BoardClue SCIENCE_DAILY_DOUBLE =
            new BoardClue(104L, 5, "Daily Double question", "Daily Double answer", 1000, true);
    private static final BoardClue FINAL =
            new BoardClue(301L, 1, "Final question", "Final answer", 2000, false);

    private GameSession session;
    private GameSessionPlayer ana;
    private GameSessionPlayer ben;
    private GameSessionPlayer cy;

    @BeforeEach
    void setUp() {
        useBoard(new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(
                        new BoardCategory("HISTORY", List.of(HISTORY_200, HISTORY_400)),
                        new BoardCategory("SCIENCE", List.of(SCIENCE_200, SCIENCE_DAILY_DOUBLE))
                )),
                new BoardRound(GameRound.FINAL_JEOPARDY, List.of(
                        new BoardCategory("FINAL", List.of(FINAL))
                ))
        )));
    }

    // -------------------------------------------------------------------------
    // Host
    // -------------------------------------------------------------------------

    @Test
    void firstPlayerToJoin_isTheHost() {
        assertEquals(ana.getId(), session.getHostPlayerId());
    }

    @Test
    void newSession_hasNoHost() {
        GameSession empty = new GameSession(SessionCode.random(), session.getBoard());

        assertNull(empty.getHostPlayerId());
    }

    @Test
    void onlyTheHostCanStart_andTheHostChoosesFirst() {
        assertError(GameErrorCode.NOT_HOST, () -> session.startGame(ben.getId()));
        assertEquals(GameSessionState.WAITING_FOR_START, session.getState());

        session.startGame(ana.getId());

        assertEquals(GameSessionState.CHOOSING_CLUE, session.getState());
        assertEquals(ana.getId(), session.getPlayerChoosingClue());
    }

    @Test
    void hostLeaving_passesHostToTheLowestRemainingId() {
        session.leave(ana.getId());

        assertEquals(ben.getId(), session.getHostPlayerId());

        session.leave(ben.getId());

        assertEquals(cy.getId(), session.getHostPlayerId());
    }

    @Test
    void newHost_canStartAndChoosesFirst() {
        session.leave(ana.getId());
        session.startGame(ben.getId());

        assertEquals(ben.getId(), session.getPlayerChoosingClue());
    }

    @Test
    void hostLeavingMidGame_passesHostAtOnce_evenWhileStillChoosing() {
        session.startGame(ana.getId());

        session.leave(ana.getId());

        assertTrue(ana.isLeaving());
        assertEquals(ben.getId(), session.getHostPlayerId());
    }

    @Test
    void lastPlayerLeaving_leavesNoHostAndNoActivePlayers() {
        session.leave(ana.getId());
        session.leave(ben.getId());
        session.leave(cy.getId());

        assertNull(session.getHostPlayerId());
        assertTrue(session.getActivePlayers().isEmpty());
        assertTrue(session.getPlayers().isEmpty());
    }

    @Test
    void hostCanKickAPlayer_whoIsRemovedLikeALeavingPlayer() {
        GameSessionPlayer kicked = session.kick(ana.getId(), ben.getId());

        assertSame(ben, kicked);
        assertEquals(List.of(ana, cy), session.getPlayers());
        assertEquals(ana.getId(), session.getHostPlayerId());
    }

    @Test
    void onlyTheHostCanKick() {
        assertError(GameErrorCode.NOT_HOST, () -> session.kick(ben.getId(), cy.getId()));

        assertEquals(3, session.getPlayers().size());
    }

    @Test
    void hostCantKickThemselves() {
        assertError(GameErrorCode.INVALID_REQUEST, () -> session.kick(ana.getId(), ana.getId()));
    }

    @Test
    void kickingSomeoneWhoIsntInTheSession_isRejected() {
        assertError(GameErrorCode.PLAYER_NOT_FOUND, () -> session.kick(ana.getId(), 99L));
    }

    @Test
    void kickingAPlayerWhoAlreadyLeft_isRejected() {
        session.startGame(ana.getId());
        chooseAndBuzz(HISTORY_200, ben);
        session.leave(ben.getId());

        assertError(GameErrorCode.PLAYER_NOT_FOUND, () -> session.kick(ana.getId(), ben.getId()));
    }

    // -------------------------------------------------------------------------
    // Leaving before and after the game
    // -------------------------------------------------------------------------

    @Test
    void leavingBeforeTheStart_removesThePlayerAtOnce() {
        GameSessionPlayer left = session.leave(ben.getId());

        assertSame(ben, left);
        assertEquals(List.of(ana, cy), session.getPlayers());
    }

    @Test
    void playerWhoLeft_cantLeaveAgainOrAct() {
        session.startGame(ana.getId());
        session.leave(ana.getId());

        assertError(GameErrorCode.PLAYER_NOT_IN_SESSION, () -> session.leave(ana.getId()));
        assertError(GameErrorCode.PLAYER_NOT_IN_SESSION, () -> session.chooseClue(ana.getId(), HISTORY_200.id()));
    }

    @Test
    void idsAreNeverReused_afterAPlayerLeaves() {
        session.leave(cy.getId());

        GameSessionPlayer newcomer = session.addPlayer("Dee");

        assertEquals(4L, newcomer.getId());
    }

    @Test
    void playerWhoLeft_canRejoinAsANewPlayer() {
        session.leave(ben.getId());

        GameSessionPlayer rejoined = session.addPlayer("Ben");

        assertNotEquals(ben.getId(), rejoined.getId());
        assertEquals(0, rejoined.getScore());
        assertFalse(rejoined.isLeaving());
    }

    @Test
    void leavingAfterTheGame_removesThePlayerAtOnce() {
        useBoard(new GameBoard(List.of(new BoardRound(GameRound.JEOPARDY, List.of(
                new BoardCategory("HISTORY", List.of(HISTORY_200))
        )))));
        session.startGame(ana.getId());
        playWithoutBuzz(HISTORY_200);
        assertEquals(GameSessionState.FINISHED_GAME, session.getState());

        session.leave(ana.getId());

        assertEquals(List.of(ben, cy), session.getPlayers());
    }

    // -------------------------------------------------------------------------
    // A departing player without a role
    // -------------------------------------------------------------------------

    @Test
    void leavingWithoutARole_removesThePlayerAtOnce() {
        session.startGame(ana.getId());

        session.leave(cy.getId());

        assertEquals(List.of(ana, ben), session.getPlayers());
    }

    @Test
    void playerWhoLeftWithoutARole_takesTheirAnswersWithThem() {
        session.startGame(ana.getId());
        chooseAndBuzz(HISTORY_200, cy);
        session.answerClue(cy.getId(), "Wrong");
        session.buzz(ben.getId());

        session.leave(cy.getId());

        assertTrue(session.getClueAnswers().stream().noneMatch(answer -> answer.playerId().equals(cy.getId())));
        assertEquals(Set.of(ben.getId()), session.getPlayersLockedOut());
    }

    @Test
    void lastPlayerWhoCouldBuzzLeaving_revealsTheAnswer() {
        session.startGame(ana.getId());
        chooseAndBuzz(HISTORY_200, ben);
        session.answerClue(ben.getId(), "Wrong");
        session.buzz(ana.getId());
        session.answerClue(ana.getId(), "Wrong");
        assertEquals(GameSessionState.WAITING_FOR_BUZZER, session.getState());

        session.leave(cy.getId());

        assertEquals(GameSessionState.REVEALING_ANSWER, session.getState());
        assertEquals(2, session.getClueAnswers().size());
    }

    // -------------------------------------------------------------------------
    // A departing buzzer
    // -------------------------------------------------------------------------

    @Test
    void leavingWhileAnswering_keepsThePlayerUntilTheAnswerTimesOut() {
        session.startGame(ana.getId());
        chooseAndBuzz(HISTORY_200, ben);

        session.leave(ben.getId());

        assertTrue(session.getPlayers().contains(ben));
        assertTrue(ben.isLeaving());
        assertEquals(ben.getId(), session.getPlayerAnswering());
        assertError(GameErrorCode.PLAYER_NOT_IN_SESSION, () -> session.answerClue(ben.getId(), "History answer"));

        session.timeoutAnswer();

        assertFalse(session.getPlayers().contains(ben));
        assertEquals(GameSessionState.WAITING_FOR_BUZZER, session.getState());
        assertEquals(Set.of(), session.getPlayersLockedOut());
        assertEquals(List.of(), session.getClueAnswers());
    }

    @Test
    void departingBuzzersRemoval_letsTheOthersStillBuzz() {
        session.startGame(ana.getId());
        chooseAndBuzz(HISTORY_200, ben);
        session.leave(ben.getId());
        session.timeoutAnswer();

        session.buzz(cy.getId());
        session.answerClue(cy.getId(), "History answer");

        assertEquals(cy.getId(), session.getPlayerChoosingClue());
        assertEquals(200, cy.getScore());
    }

    @Test
    void departingBuzzerWhoWasTheLastToBuzz_endsTheClue() {
        session.startGame(ana.getId());
        chooseAndBuzz(HISTORY_200, ana);
        session.answerClue(ana.getId(), "Wrong");
        session.buzz(cy.getId());
        session.answerClue(cy.getId(), "Wrong");
        session.buzz(ben.getId());

        session.leave(ben.getId());
        session.timeoutAnswer();

        assertEquals(GameSessionState.REVEALING_ANSWER, session.getState());
        assertEquals(List.of(ana, cy), session.getPlayers());
    }

    // -------------------------------------------------------------------------
    // A departing chooser
    // -------------------------------------------------------------------------

    @Test
    void leavingWhileChoosing_keepsControlUntilAnotherPlayerAnswersCorrectly() {
        session.startGame(ana.getId());

        session.leave(ana.getId());

        assertTrue(session.getPlayers().contains(ana));
        assertEquals(ana.getId(), session.getPlayerChoosingClue());

        // The timer picks a clue for the departed chooser, who keeps control.
        session.timeoutChoosingClue();
        session.finishRevealingClue();
        session.buzz(ben.getId());
        session.answerClue(ben.getId(), "Wrong");

        assertEquals(ana.getId(), session.getPlayerChoosingClue());
        assertTrue(session.getPlayers().contains(ana));

        session.buzz(cy.getId());
        session.answerClue(cy.getId(), HISTORY_200.answer());

        assertEquals(cy.getId(), session.getPlayerChoosingClue());
        assertEquals(List.of(ben, cy), session.getPlayers());
    }

    @Test
    void departedChooser_cantBuzz_andEveryoneElseBuzzingEndsTheClue() {
        session.startGame(ana.getId());
        session.leave(ana.getId());
        session.timeoutChoosingClue();
        session.finishRevealingClue();

        assertError(GameErrorCode.PLAYER_NOT_IN_SESSION, () -> session.buzz(ana.getId()));

        session.buzz(ben.getId());
        session.answerClue(ben.getId(), "Wrong");
        session.buzz(cy.getId());
        session.answerClue(cy.getId(), "Wrong");

        assertEquals(GameSessionState.REVEALING_ANSWER, session.getState());

        session.finishRevealingAnswer();

        assertEquals(GameSessionState.CHOOSING_CLUE, session.getState());
        assertEquals(ana.getId(), session.getPlayerChoosingClue());
        assertTrue(ana.isLeaving());
    }

    @Test
    void departedChooser_keepsControlThroughADailyDouble() {
        session.startGame(ana.getId());
        session.chooseClue(ana.getId(), SCIENCE_DAILY_DOUBLE.id());

        session.leave(ana.getId());

        assertError(GameErrorCode.PLAYER_NOT_IN_SESSION, () -> session.placeDailyDoubleWager(ana.getId(), 500));

        session.timeoutDailyDoubleWager();
        session.finishRevealingClue();
        assertEquals(ana.getId(), session.getPlayerAnswering());

        session.timeoutAnswer();

        assertEquals(-5, ana.getScore());
        assertEquals(GameSessionState.REVEALING_ANSWER, session.getState());
        assertTrue(session.getPlayers().contains(ana));

        session.finishRevealingAnswer();

        assertEquals(ana.getId(), session.getPlayerChoosingClue());
        assertTrue(session.getPlayers().contains(ana));
    }

    @Test
    void departedChooser_isRemovedWhenFinalJeopardyStarts() {
        useBoard(new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(new BoardCategory("HISTORY", List.of(HISTORY_200)))),
                new BoardRound(GameRound.FINAL_JEOPARDY, List.of(new BoardCategory("FINAL", List.of(FINAL))))
        )));
        session.startGame(ana.getId());
        session.leave(ana.getId());

        session.timeoutChoosingClue();
        session.finishRevealingClue();
        session.timeoutBuzzer();
        session.finishRevealingAnswer();

        assertEquals(GameSessionState.FINAL_JEOPARDY_WAGERING, session.getState());
        assertEquals(List.of(ben, cy), session.getPlayers());
        assertNull(session.getPlayerChoosingClue());
    }

    @Test
    void departedChooser_isRemovedWhenTheGameEnds() {
        useBoard(new GameBoard(List.of(new BoardRound(GameRound.JEOPARDY, List.of(
                new BoardCategory("HISTORY", List.of(HISTORY_200))
        )))));
        session.startGame(ana.getId());
        session.leave(ana.getId());

        session.timeoutChoosingClue();
        session.finishRevealingClue();
        session.timeoutBuzzer();
        session.finishRevealingAnswer();

        assertEquals(GameSessionState.FINISHED_GAME, session.getState());
        assertEquals(List.of(ben, cy), session.getPlayers());
    }

    // -------------------------------------------------------------------------
    // Final Jeopardy
    // -------------------------------------------------------------------------

    @Test
    void leavingDuringFinalJeopardy_removesThePlayerAndTheirWagerAtOnce() {
        reachFinalJeopardy();
        session.placeFinalJeopardyWager(ben.getId(), 0);

        session.leave(ben.getId());

        assertEquals(List.of(ana, cy), session.getPlayers());
        assertFalse(session.getFinalJeopardy().hasWager(ben.getId()));

        session.timeoutFinalJeopardyWagering();

        assertFalse(session.getFinalJeopardy().hasWager(ben.getId()));
    }

    @Test
    void leavingAfterAnsweringFinalJeopardy_dropsTheAnswer() {
        reachFinalJeopardy();
        session.timeoutFinalJeopardyWagering();
        session.answerFinalJeopardy(cy.getId(), "Final answer");

        session.leave(cy.getId());

        assertFalse(session.getFinalJeopardy().hasAnswer(cy.getId()));
        assertError(GameErrorCode.PLAYER_NOT_IN_SESSION, () -> session.answerFinalJeopardy(cy.getId(), "Final answer"));
    }

    // -------------------------------------------------------------------------
    // Timers
    // -------------------------------------------------------------------------

    @Test
    void hostCanChangeTheTimersBeforeTheStart() {
        GameSessionTimeouts faster = new GameSessionTimeouts(
                Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(10),
                Duration.ofSeconds(5), Duration.ofSeconds(3), Duration.ofSeconds(1),
                Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(5)
        );

        session.updateTimeouts(ana.getId(), faster);

        assertEquals(faster, session.getTimeouts());
    }

    @Test
    void onlyTheHostCanChangeTheTimers() {
        assertError(
                GameErrorCode.NOT_HOST,
                () -> session.updateTimeouts(ben.getId(), GameSessionTimeouts.defaults())
        );
    }

    @Test
    void timersCantChangeOnceTheGameHasStarted() {
        session.startGame(ana.getId());

        assertError(
                GameErrorCode.INVALID_STATE,
                () -> session.updateTimeouts(ana.getId(), GameSessionTimeouts.defaults())
        );
    }

    // -------------------------------------------------------------------------
    // Closing
    // -------------------------------------------------------------------------

    @Test
    void closedSession_rejectsLeaving() {
        session.close(SessionCloseReason.IDLE);

        assertEquals(GameSessionState.CLOSED, session.getState());
        assertEquals(SessionCloseReason.IDLE, session.getCloseReason());
        assertError(GameErrorCode.INVALID_STATE, () -> session.leave(ben.getId()));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void useBoard(GameBoard board) {
        session = new GameSession(SessionCode.random(), board);
        ana = session.addPlayer("Ana");
        ben = session.addPlayer("Ben");
        cy = session.addPlayer("Cy");
    }

    private void chooseAndBuzz(BoardClue clue, GameSessionPlayer buzzer) {
        session.chooseClue(session.getPlayerChoosingClue(), clue.id());
        session.finishRevealingClue();
        session.buzz(buzzer.getId());
    }

    private void playWithoutBuzz(BoardClue clue) {
        session.chooseClue(session.getPlayerChoosingClue(), clue.id());
        session.finishRevealingClue();
        session.timeoutBuzzer();
        session.finishRevealingAnswer();
    }

    private void reachFinalJeopardy() {
        session.startGame(ana.getId());
        playWithoutBuzz(HISTORY_200);
        playWithoutBuzz(HISTORY_400);
        playWithoutBuzz(SCIENCE_200);
        session.chooseClue(ana.getId(), SCIENCE_DAILY_DOUBLE.id());
        session.timeoutDailyDoubleWager();
        session.finishRevealingClue();
        session.answerClue(ana.getId(), SCIENCE_DAILY_DOUBLE.answer());
        session.finishRevealingAnswer();

        assertEquals(GameSessionState.FINAL_JEOPARDY_WAGERING, session.getState());
    }

    private void assertError(GameErrorCode code, Executable action) {
        InvalidGameActionException exception =
                assertThrows(InvalidGameActionException.class, action);

        assertEquals(code, exception.getCode());
    }
}
