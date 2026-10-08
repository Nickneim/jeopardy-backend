package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.controller.GameSessionBroadcaster;
import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class GameSessionServiceTest {

    private GameSessionService gameSessionService;
    private GameSessionTimer timer;
    private GameSessionBroadcaster broadcaster;

    private static final Long CLUE_ID = 10L;
    private static final Long DAILY_DOUBLE_CLUE_ID = 11L;
    // A second clue keeps the round going after CLUE_ID is played, so
    // finishing an answer reveal returns to CHOOSING_CLUE.
    private static final Long OTHER_CLUE_ID = 12L;

    @BeforeEach
    void setUp() {
        timer = mock(GameSessionTimer.class);
        broadcaster = mock(GameSessionBroadcaster.class);

        gameSessionService = new GameSessionService(
                timer,
                broadcaster
        );
    }

    // -------------------------------------------------------------------------
    // Session management
    // -------------------------------------------------------------------------

    @Test
    void createSession_createsAndStoresSession() {
        GameBoard board = createBoard();

        GameSession session = gameSessionService.createSession(board);

        assertNotNull(session);
        assertNotNull(session.getId());
        assertEquals(
                session,
                gameSessionService.getSession(session.getId())
        );
    }

    @Test
    void createSession_startsInWaitingForStartState() {
        GameSession session = createSession();

        assertEquals(
                GameSessionState.WAITING_FOR_START,
                session.getState()
        );
    }

    @Test
    void getSession_throwsWhenSessionDoesNotExist() {
        UUID id = UUID.randomUUID();

        assertThrows(
                GameSessionNotFoundException.class,
                () -> gameSessionService.getSession(id)
        );
    }

    @Test
    void removeSession_removesSessionAndCancelsTimer() {
        GameSession session = createSession();

        clearInvocations(timer, broadcaster);

        gameSessionService.removeSession(session.getId());

        assertThrows(
                GameSessionNotFoundException.class,
                () -> gameSessionService.getSession(session.getId())
        );

        verify(timer).cancel(session.getId());
        verifyNoInteractions(broadcaster);
    }

    // -------------------------------------------------------------------------
    // Timer scheduling
    // -------------------------------------------------------------------------

    @Test
    void chooseClue_schedulesClueRevealTimeout() {
        GameSession session = createStartedSession();

        Long playerId = getFirstPlayerId(session);

        clearInvocations(timer, broadcaster);

        gameSessionService.chooseClue(
                session.getId(),
                playerId,
                CLUE_ID
        );

        assertEquals(
                GameSessionState.REVEALING_CLUE,
                session.getState()
        );

        verify(timer).schedule(
                eq(session.getId()),
                eq(Duration.ofSeconds(3)),
                any(Runnable.class)
        );
    }

    @Test
    void finishRevealingClue_schedulesBuzzerTimeout() {
        GameSession session = createSessionWithChosenClue();

        clearInvocations(timer, broadcaster);

        gameSessionService.finishRevealingClue(session.getId());

        assertEquals(
                GameSessionState.WAITING_FOR_BUZZER,
                session.getState()
        );

        verify(timer).schedule(
                eq(session.getId()),
                eq(Duration.ofSeconds(10)),
                any(Runnable.class)
        );
    }

    @Test
    void buzz_schedulesAnswerTimeout() {
        GameSession session = createSessionWaitingForBuzzer();

        Long playerId = getFirstPlayerId(session);

        clearInvocations(timer, broadcaster);

        gameSessionService.buzz(
                session.getId(),
                playerId
        );

        assertEquals(
                GameSessionState.WAITING_FOR_ANSWER,
                session.getState()
        );

        verify(timer).schedule(
                eq(session.getId()),
                eq(Duration.ofSeconds(5)),
                any(Runnable.class)
        );
    }

    @Test
    void answerClue_correctAnswer_schedulesAnswerReveal() {
        GameSession session = createSessionWaitingForAnswer();

        Long playerId = getFirstPlayerId(session);

        clearInvocations(timer, broadcaster);

        gameSessionService.answerClue(
                session.getId(),
                playerId,
                "Correct answer"
        );

        assertEquals(
                GameSessionState.REVEALING_ANSWER,
                session.getState()
        );

        verify(timer).schedule(
                eq(session.getId()),
                eq(Duration.ofSeconds(3)),
                any(Runnable.class)
        );
    }

    @Test
    void answerClue_incorrectAnswer_schedulesNextBuzzerTimeout() {
        GameSession session = createSessionWaitingForAnswer(2);

        Long playerId = getFirstPlayerId(session);

        clearInvocations(timer, broadcaster);

        gameSessionService.answerClue(
                session.getId(),
                playerId,
                "Wrong answer"
        );

        assertEquals(
                GameSessionState.WAITING_FOR_BUZZER,
                session.getState()
        );

        verify(timer).schedule(
                eq(session.getId()),
                eq(Duration.ofSeconds(10)),
                any(Runnable.class)
        );
    }

    @Test
    void finishRevealingAnswer_cancelsExistingTimeoutAndDoesNotScheduleAnother() {
        GameSession session = createSessionWithAnswerBeingRevealed();

        clearInvocations(timer, broadcaster);

        gameSessionService.finishRevealingAnswer(session.getId());

        assertEquals(
                GameSessionState.CHOOSING_CLUE,
                session.getState()
        );

        verify(timer).cancel(session.getId());
        verifyNoMoreInteractions(timer);
    }

    // -------------------------------------------------------------------------
    // Broadcasting
    // -------------------------------------------------------------------------

    @Test
    void joinSession_broadcastsUpdatedSession() {
        GameSession session = createSession();

        clearInvocations(timer, broadcaster);

        gameSessionService.joinSession(
                session.getId(),
                "Player 1"
        );

        verify(broadcaster).broadcast(session);
    }

    @Test
    void startGame_broadcastsUpdatedSession() {
        GameSession session = createSessionWithPlayers(1);

        clearInvocations(timer, broadcaster);

        gameSessionService.startGame(
                session.getId(),
                getFirstPlayerId(session)
        );

        verify(broadcaster).broadcast(session);
    }

    @Test
    void chooseClue_broadcastsUpdatedSession() {
        GameSession session = createStartedSession();

        Long playerId = getFirstPlayerId(session);

        clearInvocations(timer, broadcaster);

        gameSessionService.chooseClue(
                session.getId(),
                playerId,
                CLUE_ID
        );

        verify(broadcaster).broadcast(session);
    }

    @Test
    void finishRevealingClue_broadcastsUpdatedSession() {
        GameSession session = createSessionWithChosenClue();

        clearInvocations(timer, broadcaster);

        gameSessionService.finishRevealingClue(session.getId());

        verify(broadcaster).broadcast(session);
    }

    @Test
    void buzz_broadcastsUpdatedSession() {
        GameSession session = createSessionWaitingForBuzzer();

        Long playerId = getFirstPlayerId(session);

        clearInvocations(timer, broadcaster);

        gameSessionService.buzz(
                session.getId(),
                playerId
        );

        verify(broadcaster).broadcast(session);
    }

    @Test
    void answerClue_correctAnswer_broadcastsUpdatedSession() {
        GameSession session = createSessionWaitingForAnswer();

        Long playerId = getFirstPlayerId(session);

        clearInvocations(timer, broadcaster);

        gameSessionService.answerClue(
                session.getId(),
                playerId,
                "Correct answer"
        );

        verify(broadcaster).broadcast(session);
    }

    @Test
    void answerClue_incorrectAnswer_broadcastsUpdatedSession() {
        GameSession session = createSessionWaitingForAnswer(2);

        Long playerId = getFirstPlayerId(session);

        clearInvocations(timer, broadcaster);

        gameSessionService.answerClue(
                session.getId(),
                playerId,
                "Wrong answer"
        );

        verify(broadcaster).broadcast(session);
    }

    @Test
    void finishRevealingAnswer_broadcastsUpdatedSession() {
        GameSession session = createSessionWithAnswerBeingRevealed();

        clearInvocations(timer, broadcaster);

        gameSessionService.finishRevealingAnswer(session.getId());

        verify(broadcaster).broadcast(session);
    }

    @Test
    void removeSession_doesNotBroadcast() {
        GameSession session = createSession();

        clearInvocations(timer, broadcaster);

        gameSessionService.removeSession(session.getId());

        verifyNoInteractions(broadcaster);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private GameSession createSession() {
        return gameSessionService.createSession(createBoard());
    }

    private GameSession createSessionWithPlayers(int playerCount) {
        GameSession session = createSession();

        for (int i = 1; i <= playerCount; i++) {
            session.addPlayer(
                    new GameSessionPlayer(
                            (long) i,
                            "Player " + i
                    )
            );
        }

        return session;
    }

    private GameSession createStartedSession() {
        GameSession session = createSessionWithPlayers(1);

        gameSessionService.startGame(
                session.getId(),
                getFirstPlayerId(session)
        );

        return session;
    }

    private GameSession createSessionWithChosenClue() {
        GameSession session = createStartedSession();

        gameSessionService.chooseClue(
                session.getId(),
                getFirstPlayerId(session),
                CLUE_ID
        );

        return session;
    }

    private GameSession createSessionWaitingForBuzzer() {
        GameSession session = createSessionWithChosenClue();

        gameSessionService.finishRevealingClue(session.getId());

        return session;
    }

    private GameSession createSessionWaitingForAnswer() {
        return createSessionWaitingForAnswer(1);
    }

    private GameSession createSessionWaitingForAnswer(int playerCount) {
        GameSession session = createSessionWithPlayers(playerCount);

        gameSessionService.startGame(
                session.getId(),
                getFirstPlayerId(session)
        );

        gameSessionService.chooseClue(
                session.getId(),
                getFirstPlayerId(session),
                CLUE_ID
        );

        gameSessionService.finishRevealingClue(session.getId());

        gameSessionService.buzz(
                session.getId(),
                getFirstPlayerId(session)
        );

        return session;
    }

    private GameSession createSessionWithAnswerBeingRevealed() {
        GameSession session = createSessionWaitingForAnswer();

        gameSessionService.answerClue(
                session.getId(),
                getFirstPlayerId(session),
                "Correct answer"
        );

        return session;
    }

    private Long getFirstPlayerId(GameSession session) {
        return session.getPlayers().getFirst().getId();
    }

    private GameBoard createBoard() {
        return board(
                new BoardClue(CLUE_ID, 1, "Question", "Correct answer", 200, false),
                new BoardClue(OTHER_CLUE_ID, 2, "Other question", "Other answer", 400, false)
        );
    }

    // The Daily Double is worth 1000, the round's highest value, so wagers
    // up to 1000 are valid.
    private GameBoard createBoardWithDailyDouble() {
        return board(
                new BoardClue(CLUE_ID, 1, "Question", "Correct answer", 200, false),
                new BoardClue(OTHER_CLUE_ID, 2, "Other question", "Other answer", 400, false),
                new BoardClue(DAILY_DOUBLE_CLUE_ID, 5, "Daily Double question", "Daily Double answer", 1000, true)
        );
    }

    private GameBoard board(BoardClue... clues) {
        return new GameBoard(List.of(
                new BoardRound(
                        GameRound.JEOPARDY,
                        List.of(new BoardCategory("Category", List.of(clues)))
                )
        ));
    }

    @Test
    void concurrentBuzzes_onlyOnePlayerCanBuzz() throws Exception {
        GameSession session = createSessionWithPlayers(2);

        gameSessionService.startGame(
                session.getId(),
                getFirstPlayerId(session)
        );

        Long player1 = session.getPlayers().get(0).getId();
        Long player2 = session.getPlayers().get(1).getId();
        Long clueId = session.getAvailableClues().iterator().next();

        gameSessionService.chooseClue(
                session.getId(),
                player1,
                clueId
        );

        gameSessionService.finishRevealingClue(
                session.getId()
        );

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);

            Future<?> first = executor.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(exception);
                }

                gameSessionService.buzz(
                        session.getId(),
                        player1
                );
            });

            Future<?> second = executor.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(exception);
                }

                gameSessionService.buzz(
                        session.getId(),
                        player2
                );
            });

            start.countDown();

            int successfulBuzzes = 0;

            for (Future<?> future : List.of(first, second)) {
                try {
                    future.get();
                    successfulBuzzes++;
                } catch (ExecutionException exception) {
                    assertInstanceOf(
                            InvalidGameActionException.class,
                            exception.getCause()
                    );
                    assertEquals(GameErrorCode.INVALID_STATE, ((InvalidGameActionException)exception.getCause()).getCode());
                }
            }

            assertEquals(1, successfulBuzzes);
            assertEquals(
                    GameSessionState.WAITING_FOR_ANSWER,
                    session.getState()
            );
            assertNotNull(session.getPlayerWhoBuzzed());
        }
    }

    // -------------------------------------------------------------------------
    // Daily Double scheduling
    // -------------------------------------------------------------------------

    @Test
    void chooseClue_onDailyDouble_schedulesWagerTimeout() {
        GameSession session = createStartedSessionWithDailyDouble();

        clearInvocations(timer, broadcaster);

        gameSessionService.chooseClue(
                session.getId(),
                getFirstPlayerId(session),
                DAILY_DOUBLE_CLUE_ID
        );

        assertEquals(
                GameSessionState.WAITING_FOR_DAILY_DOUBLE_WAGER,
                session.getState()
        );

        verify(timer).schedule(
                eq(session.getId()),
                eq(Duration.ofSeconds(30)),
                any(Runnable.class)
        );
        verify(broadcaster).broadcast(session);
    }

    @Test
    void placeDailyDoubleWager_schedulesClueRevealTimeoutAndBroadcasts() {
        GameSession session = createSessionWaitingForWager();

        clearInvocations(timer, broadcaster);

        gameSessionService.placeDailyDoubleWager(
                session.getId(),
                getFirstPlayerId(session),
                500
        );

        assertEquals(
                GameSessionState.REVEALING_CLUE,
                session.getState()
        );

        verify(timer).schedule(
                eq(session.getId()),
                eq(Duration.ofSeconds(3)),
                any(Runnable.class)
        );
        verify(broadcaster).broadcast(session);
    }

    @Test
    void finishRevealingClue_onDailyDouble_schedulesAnswerTimeoutInsteadOfBuzzer() {
        GameSession session = createSessionWaitingForWager();

        gameSessionService.placeDailyDoubleWager(
                session.getId(),
                getFirstPlayerId(session),
                500
        );

        clearInvocations(timer, broadcaster);

        gameSessionService.finishRevealingClue(session.getId());

        assertEquals(
                GameSessionState.WAITING_FOR_ANSWER,
                session.getState()
        );

        verify(timer).schedule(
                eq(session.getId()),
                eq(Duration.ofSeconds(5)),
                any(Runnable.class)
        );
        verify(timer, never()).schedule(
                eq(session.getId()),
                eq(Duration.ofSeconds(10)),
                any(Runnable.class)
        );
    }

    // -------------------------------------------------------------------------
    // Timeout callbacks
    // -------------------------------------------------------------------------

    @Test
    void wagerTimeout_setsMinimumWagerAndRevealsClue() {
        GameSession session = createStartedSessionWithDailyDouble();

        clearInvocations(timer, broadcaster);

        gameSessionService.chooseClue(
                session.getId(),
                getFirstPlayerId(session),
                DAILY_DOUBLE_CLUE_ID
        );

        Runnable wagerTimeout =
                captureScheduledRunnable(session, Duration.ofSeconds(30));

        clearInvocations(timer, broadcaster);

        wagerTimeout.run();

        assertEquals(
                GameSessionState.REVEALING_CLUE,
                session.getState()
        );
        assertEquals(
                5,
                session.getPlayers().getFirst().getWager()
        );

        verify(timer).schedule(
                eq(session.getId()),
                eq(Duration.ofSeconds(3)),
                any(Runnable.class)
        );
        verify(broadcaster).broadcast(session);
    }

    @Test
    void clueRevealTimeout_opensBuzzer() {
        GameSession session = createStartedSession();

        clearInvocations(timer, broadcaster);

        gameSessionService.chooseClue(
                session.getId(),
                getFirstPlayerId(session),
                CLUE_ID
        );

        Runnable clueRevealTimeout =
                captureScheduledRunnable(session, Duration.ofSeconds(3));

        clearInvocations(timer, broadcaster);

        clueRevealTimeout.run();

        assertEquals(
                GameSessionState.WAITING_FOR_BUZZER,
                session.getState()
        );
        verify(broadcaster).broadcast(session);
    }

    @Test
    void buzzerTimeout_revealsAnswer() {
        GameSession session = createSessionWithChosenClue();

        clearInvocations(timer, broadcaster);

        gameSessionService.finishRevealingClue(session.getId());

        Runnable buzzerTimeout =
                captureScheduledRunnable(session, Duration.ofSeconds(10));

        clearInvocations(timer, broadcaster);

        buzzerTimeout.run();

        assertEquals(
                GameSessionState.REVEALING_ANSWER,
                session.getState()
        );
        verify(broadcaster).broadcast(session);
    }

    @Test
    void answerTimeout_deductsClueValueAndRevealsAnswer() {
        GameSession session = createSessionWaitingForBuzzer();

        clearInvocations(timer, broadcaster);

        gameSessionService.buzz(
                session.getId(),
                getFirstPlayerId(session)
        );

        Runnable answerTimeout =
                captureScheduledRunnable(session, Duration.ofSeconds(5));

        clearInvocations(timer, broadcaster);

        answerTimeout.run();

        assertEquals(
                -200,
                session.getPlayers().getFirst().getScore()
        );
        assertEquals(
                GameSessionState.REVEALING_ANSWER,
                session.getState()
        );
        verify(broadcaster).broadcast(session);
    }

    @Test
    void answerTimeout_onDailyDouble_deductsWagerAndRevealsAnswer() {
        GameSession session = createSessionWaitingForWager();

        gameSessionService.placeDailyDoubleWager(
                session.getId(),
                getFirstPlayerId(session),
                500
        );

        clearInvocations(timer, broadcaster);

        gameSessionService.finishRevealingClue(session.getId());

        Runnable answerTimeout =
                captureScheduledRunnable(session, Duration.ofSeconds(5));

        clearInvocations(timer, broadcaster);

        answerTimeout.run();

        assertEquals(
                -500,
                session.getPlayers().getFirst().getScore()
        );
        assertEquals(
                GameSessionState.REVEALING_ANSWER,
                session.getState()
        );
        verify(broadcaster).broadcast(session);
    }

    @Test
    void answerRevealTimeout_returnsToChoosingClue() {
        GameSession session = createSessionWaitingForAnswer();

        clearInvocations(timer, broadcaster);

        gameSessionService.answerClue(
                session.getId(),
                getFirstPlayerId(session),
                "Correct answer"
        );

        Runnable answerRevealTimeout =
                captureScheduledRunnable(session, Duration.ofSeconds(3));

        clearInvocations(timer, broadcaster);

        answerRevealTimeout.run();

        assertEquals(
                GameSessionState.CHOOSING_CLUE,
                session.getState()
        );
        verify(broadcaster).broadcast(session);
    }

    // -------------------------------------------------------------------------
    // Stale timeouts and failures
    // -------------------------------------------------------------------------

    @Test
    void staleWagerTimeout_afterWagerPlaced_doesNothing() {
        GameSession session = createStartedSessionWithDailyDouble();

        clearInvocations(timer, broadcaster);

        gameSessionService.chooseClue(
                session.getId(),
                getFirstPlayerId(session),
                DAILY_DOUBLE_CLUE_ID
        );

        Runnable staleWagerTimeout =
                captureScheduledRunnable(session, Duration.ofSeconds(30));

        gameSessionService.placeDailyDoubleWager(
                session.getId(),
                getFirstPlayerId(session),
                500
        );

        clearInvocations(timer, broadcaster);

        staleWagerTimeout.run();

        assertEquals(
                GameSessionState.REVEALING_CLUE,
                session.getState()
        );
        assertEquals(
                500,
                session.getPlayers().getFirst().getWager()
        );
        verifyNoInteractions(timer, broadcaster);
    }

    @Test
    void staleBuzzerTimeout_afterBuzz_doesNothing() {
        GameSession session = createSessionWithChosenClue();

        clearInvocations(timer, broadcaster);

        gameSessionService.finishRevealingClue(session.getId());

        Runnable staleBuzzerTimeout =
                captureScheduledRunnable(session, Duration.ofSeconds(10));

        gameSessionService.buzz(
                session.getId(),
                getFirstPlayerId(session)
        );

        clearInvocations(timer, broadcaster);

        staleBuzzerTimeout.run();

        assertEquals(
                GameSessionState.WAITING_FOR_ANSWER,
                session.getState()
        );
        verifyNoInteractions(timer, broadcaster);
    }

    @Test
    void invalidAction_doesNotScheduleOrBroadcast() {
        GameSession session = createStartedSession();

        clearInvocations(timer, broadcaster);

        assertThrows(
                InvalidGameActionException.class,
                () -> gameSessionService.chooseClue(
                        session.getId(),
                        getFirstPlayerId(session) + 1,
                        CLUE_ID
                )
        );

        verifyNoInteractions(timer, broadcaster);
    }

    @Test
    void timeoutAfterSessionRemoved_throwsSessionNotFound() {
        // Documents current behaviour: removeSession cancels the timer, but a
        // callback that is already running will fail to find the session.
        GameSession session = createStartedSession();

        clearInvocations(timer, broadcaster);

        gameSessionService.chooseClue(
                session.getId(),
                getFirstPlayerId(session),
                CLUE_ID
        );

        Runnable clueRevealTimeout =
                captureScheduledRunnable(session, Duration.ofSeconds(3));

        gameSessionService.removeSession(session.getId());

        assertThrows(
                GameSessionNotFoundException.class,
                clueRevealTimeout::run
        );
    }

    // -------------------------------------------------------------------------
    // Daily Double helpers
    // -------------------------------------------------------------------------

    private Runnable captureScheduledRunnable(
            GameSession session,
            Duration duration
    ) {
        ArgumentCaptor<Runnable> captor =
                ArgumentCaptor.forClass(Runnable.class);

        verify(timer).schedule(
                eq(session.getId()),
                eq(duration),
                captor.capture()
        );

        return captor.getValue();
    }

    private GameSession createStartedSessionWithDailyDouble() {
        GameSession session = gameSessionService.createSession(
                createBoardWithDailyDouble()
        );

        session.addPlayer(
                new GameSessionPlayer(1L, "Player 1")
        );

        gameSessionService.startGame(
                session.getId(),
                getFirstPlayerId(session)
        );

        return session;
    }

    private GameSession createSessionWaitingForWager() {
        GameSession session = createStartedSessionWithDailyDouble();

        gameSessionService.chooseClue(
                session.getId(),
                getFirstPlayerId(session),
                DAILY_DOUBLE_CLUE_ID
        );

        return session;
    }
}
