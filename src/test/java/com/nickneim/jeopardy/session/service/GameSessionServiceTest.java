package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.controller.GameSessionBroadcaster;
import com.nickneim.jeopardy.session.dto.GameSessionResponse;
import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import com.nickneim.jeopardy.session.dto.SessionTimersRequest;
import com.nickneim.jeopardy.session.model.GameSessionTimeouts;
import com.nickneim.jeopardy.session.model.SessionCloseReason;
import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.GameSessionAlreadyExistsException;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionState;
import com.nickneim.jeopardy.session.security.PlayerConnections;
import com.nickneim.jeopardy.session.security.PlayerPrincipal;
import com.nickneim.jeopardy.session.security.PlayerTokenRegistry;
import com.nickneim.jeopardy.session.model.SessionCode;
import com.nickneim.jeopardy.session.security.PlayerTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class GameSessionServiceTest {

    private GameSessionService gameSessionService;
    private GameSessionTimer timer;
    private GameSessionBroadcaster broadcaster;
    private PlayerConnections connections;
    private PlayerTokenRegistry playerTokens;
    private Clock clock;

    private static final Long CLUE_ID = 10L;
    private static final Long DAILY_DOUBLE_CLUE_ID = 11L;
    // A second clue keeps the round going after CLUE_ID is played, so
    // finishing an answer reveal returns to CHOOSING_CLUE.
    private static final Long OTHER_CLUE_ID = 12L;
    private static final Long FINAL_CLUE_ID = 20L;
    private static final Long PLAYER_ID = 1L;

    @BeforeEach
    void setUp() {
        timer = mock(GameSessionTimer.class);
        broadcaster = mock(GameSessionBroadcaster.class);

        connections = mock(PlayerConnections.class);
        playerTokens = new PlayerTokenRegistry();
        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-09T12:00:00Z"));

        gameSessionService = new GameSessionService(
                timer,
                broadcaster,
                playerTokens,
                connections,
                clock
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
    void createSession_usesTheGivenId() {
        SessionCode id = SessionCode.random();

        GameSession session = gameSessionService.createSession(id, createBoard());

        assertEquals(id, session.getId());
        assertEquals(session, gameSessionService.getSession(id));
    }

    // The client chooses the id, so a repeated create mustn't replace the
    // session (and its players) that's already there.
    @Test
    void createSession_rejectsAnIdInUseAndKeepsTheExistingSession() {
        SessionCode id = SessionCode.random();
        GameSession existing = gameSessionService.createSession(id, createBoard());
        clearInvocations(timer);

        assertThrows(
                GameSessionAlreadyExistsException.class,
                () -> gameSessionService.createSession(id, createBoard())
        );

        assertSame(existing, gameSessionService.getSession(id));
        verifyNoInteractions(timer);
    }

    @Test
    void requireUnusedId_rejectsOnlyAnIdInUse() {
        SessionCode taken = gameSessionService.createSession(createBoard()).getId();

        assertThrows(
                GameSessionAlreadyExistsException.class,
                () -> gameSessionService.requireUnusedId(taken)
        );
        assertDoesNotThrow(() -> gameSessionService.requireUnusedId(SessionCode.random()));
    }

    // Their connections stay open after the close, and player ids start at
    // 1 again, so in a new session under the id they'd act as its players.
    @Test
    void closedSessionsId_staysTakenWhileItsPlayersAreConnected() {
        SessionCode id = SessionCode.random();
        when(connections.hasPlayersConnected(id)).thenReturn(true);

        assertThrows(
                GameSessionAlreadyExistsException.class,
                () -> gameSessionService.requireUnusedId(id)
        );
        assertThrows(
                GameSessionAlreadyExistsException.class,
                () -> gameSessionService.createSession(id, createBoard())
        );
        assertThrows(GameSessionNotFoundException.class, () -> gameSessionService.getSession(id));

        when(connections.hasPlayersConnected(id)).thenReturn(false);

        assertDoesNotThrow(() -> gameSessionService.requireUnusedId(id));
        assertEquals(id, gameSessionService.createSession(id, createBoard()).getId());
    }

    // A player still connecting passes the interceptor's second token check
    // as long as their token is registered, so the id must stay taken
    // until every token is revoked.
    @Test
    void closingASession_revokesEveryTokenBeforeTheIdIsFree() {
        PlayerTokenRegistry tokens = new PlayerTokenRegistry() {
            @Override
            public void unregister(String token) {
                find(token).ifPresent(player -> assertThrows(
                        GameSessionAlreadyExistsException.class,
                        () -> gameSessionService.requireUnusedId(player.sessionId())
                ));
                super.unregister(token);
            }
        };
        gameSessionService = new GameSessionService(timer, broadcaster, tokens, connections, clock);
        GameSession session = createSession();
        GameSessionPlayer player = gameSessionService.joinSession(session.getId(), "Nico");

        // The idle cleanup closes it with the player's token still registered.
        captureCleanup(session, GameSessionService.IDLE_LOBBY_TIMEOUT).run();

        assertThrows(GameSessionNotFoundException.class, () -> gameSessionService.getSession(session.getId()));
        assertEquals(Optional.empty(), tokens.find(player.getToken()));
    }

    @Test
    void removingASession_revokesEveryTokenBeforeTheIdIsFree() {
        PlayerTokenRegistry tokens = new PlayerTokenRegistry() {
            @Override
            public void unregister(String token) {
                find(token).ifPresent(player -> assertThrows(
                        GameSessionAlreadyExistsException.class,
                        () -> gameSessionService.requireUnusedId(player.sessionId())
                ));
                super.unregister(token);
            }
        };
        gameSessionService = new GameSessionService(timer, broadcaster, tokens, connections, clock);
        GameSession session = createSession();
        GameSessionPlayer player = gameSessionService.joinSession(session.getId(), "Nico");

        gameSessionService.removeSession(session.getId());

        assertThrows(GameSessionNotFoundException.class, () -> gameSessionService.getSession(session.getId()));
        assertEquals(Optional.empty(), tokens.find(player.getToken()));
    }

    // Cancelling can't stop a timer that is already running, and a new
    // session under the same id reaches the same state and timer id.
    @Test
    void phaseTimerOfAClosedSession_leavesANewSessionUnderItsIdAlone() {
        SessionCode id = SessionCode.random();
        Duration clueChoice = GameSessionTimeouts.defaults().clueChoice();
        GameSession old = gameSessionService.createSession(id, createBoard());
        GameSessionPlayer oldPlayer = gameSessionService.joinSession(id, "Nico");
        gameSessionService.startGame(id, oldPlayer.getId());
        Runnable oldTimeout = captureScheduledRunnable(old, clueChoice);
        long armedTimerId = old.getPhaseTimerId();
        gameSessionService.leave(id, oldPlayer.getId());

        GameSession current = gameSessionService.createSession(id, createBoard());
        GameSessionPlayer player = gameSessionService.joinSession(id, "John");
        gameSessionService.startGame(id, player.getId());
        assertEquals(armedTimerId, current.getPhaseTimerId());
        clearInvocations(timer, broadcaster);

        oldTimeout.run();

        assertEquals(GameSessionState.CHOOSING_CLUE, current.getState());
        verifyNoInteractions(timer, broadcaster);
    }

    @Test
    void cleanupOfAClosedSession_leavesANewSessionUnderItsIdAlone() {
        SessionCode id = SessionCode.random();
        GameSession old = gameSessionService.createSession(id, createBoard());
        GameSessionPlayer oldPlayer = gameSessionService.joinSession(id, "Nico");
        Runnable oldCleanup = captureCleanup(old, GameSessionService.IDLE_LOBBY_TIMEOUT);
        long oldVersion = old.getVersion();
        gameSessionService.leave(id, oldPlayer.getId());

        GameSession current = gameSessionService.createSession(id, createBoard());
        gameSessionService.joinSession(id, "John");
        assertEquals(oldVersion, current.getVersion());
        clearInvocations(broadcaster);

        oldCleanup.run();

        assertSame(current, gameSessionService.getSession(id));
        assertEquals(GameSessionState.WAITING_FOR_START, current.getState());
        verifyNoInteractions(broadcaster);
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
        SessionCode id = SessionCode.random();

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
    // Player tokens
    // -------------------------------------------------------------------------

    @Test
    void joinSession_returnsThePlayerWithTheirToken() {
        GameSession session = createSession();

        GameSessionPlayer player =
                gameSessionService.joinSession(session.getId(), "Nico");

        assertEquals(List.of(player), session.getPlayers());
        assertEquals("Nico", player.getName());
        assertNotNull(player.getToken());
    }

    @Test
    void joinSession_givesEachPlayerADifferentToken() {
        GameSession session = createSession();

        GameSessionPlayer first =
                gameSessionService.joinSession(session.getId(), "Nico");
        GameSessionPlayer second =
                gameSessionService.joinSession(session.getId(), "Nico");

        assertNotEquals(first.getToken(), second.getToken());
    }

    @Test
    void joinSession_registersTheTokenForThePlayer() {
        GameSession session = createSession();

        GameSessionPlayer player =
                gameSessionService.joinSession(session.getId(), "Nico");

        assertEquals(
                Optional.of(new PlayerPrincipal(session.getId(), player.getId())),
                playerTokens.find(player.getToken())
        );
    }

    @Test
    void playerTokens_areEmptyForUnknownOrMissingToken() {
        assertEquals(Optional.empty(), playerTokens.find("not-a-token"));
        assertEquals(Optional.empty(), playerTokens.find(null));
    }

    @Test
    void removeSession_invalidatesOnlyThatSessionsTokens() {
        GameSession removed = createSession();
        GameSession kept = createSession();

        GameSessionPlayer removedPlayer =
                gameSessionService.joinSession(removed.getId(), "Nico");
        GameSessionPlayer keptPlayer =
                gameSessionService.joinSession(kept.getId(), "John");

        gameSessionService.removeSession(removed.getId());

        assertEquals(
                Optional.empty(),
                playerTokens.find(removedPlayer.getToken())
        );
        assertTrue(
                playerTokens.find(keptPlayer.getToken()).isPresent()
        );
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
    void finishRevealingAnswer_schedulesClueChoiceTimeout() {
        GameSession session = createSessionWithAnswerBeingRevealed();

        clearInvocations(timer, broadcaster);

        gameSessionService.finishRevealingAnswer(session.getId());

        assertEquals(
                GameSessionState.CHOOSING_CLUE,
                session.getState()
        );

        verify(timer).cancel(session.getId());
        verify(timer).schedule(
                eq(session.getId()),
                eq(Duration.ofSeconds(30)),
                any(Runnable.class)
        );
        // A game in progress is never cleaned up.
        verify(timer).cancelCleanup(session.getId());
        verifyNoMoreInteractions(timer);
    }

    @Test
    void startGame_schedulesClueChoiceTimeout() {
        GameSession session = createSessionWithPlayers(1);

        gameSessionService.startGame(session.getId(), getFirstPlayerId(session));

        assertEquals(GameSessionState.CHOOSING_CLUE, session.getState());
        verify(timer).schedule(
                eq(session.getId()),
                eq(Duration.ofSeconds(30)),
                any(Runnable.class)
        );
    }

    @Test
    void clueChoiceTimeout_choosesTheTopClueForTheChooserAndBroadcasts() {
        GameSession session = createSessionWithPlayers(1);
        gameSessionService.startGame(session.getId(), getFirstPlayerId(session));

        Runnable clueChoiceTimeout =
                captureScheduledRunnable(session, Duration.ofSeconds(30));

        clearInvocations(timer, broadcaster);

        clueChoiceTimeout.run();

        assertEquals(GameSessionState.REVEALING_CLUE, session.getState());
        assertEquals(CLUE_ID, session.getChosenClueId());
        assertEquals(getFirstPlayerId(session), session.getPlayerChoosingClue());

        verify(timer).schedule(
                eq(session.getId()),
                eq(Duration.ofSeconds(3)),
                any(Runnable.class)
        );
        verify(broadcaster).broadcast(session);
    }

    @Test
    void staleClueChoiceTimeout_afterClueChosen_doesNothing() {
        GameSession session = createSessionWithPlayers(1);
        gameSessionService.startGame(session.getId(), getFirstPlayerId(session));

        Runnable clueChoiceTimeout =
                captureScheduledRunnable(session, Duration.ofSeconds(30));

        gameSessionService.chooseClue(session.getId(), getFirstPlayerId(session), OTHER_CLUE_ID);

        clearInvocations(timer, broadcaster);

        clueChoiceTimeout.run();

        assertEquals(GameSessionState.REVEALING_CLUE, session.getState());
        assertEquals(OTHER_CLUE_ID, session.getChosenClueId());
        verifyNoInteractions(timer, broadcaster);
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
    void joinSession_broadcastsWhileHoldingSessionLock() {
        // Building the broadcast reads session state, so it must not race
        // with another thread changing the session.
        GameSession session = createSession();
        AtomicBoolean heldLock = new AtomicBoolean(false);

        doAnswer(invocation -> {
            heldLock.set(Thread.holdsLock(session));
            return null;
        }).when(broadcaster).broadcast(session);

        gameSessionService.joinSession(
                session.getId(),
                "Player 1"
        );

        assertTrue(heldLock.get());
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
                            "Player " + i, PlayerTokens.generate()
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
            assertNotNull(session.getPlayerAnswering());
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
                session.getDailyDoubleWager()
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
                session.getDailyDoubleWager()
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

    // A buzz and a wrong answer bring the clue back to WAITING_FOR_BUZZER
    // while the first buzzer timeout may already be waiting for the lock.
    @Test
    void staleBuzzerTimeout_afterReturningToWaitingForBuzzer_doesNothing() {
        GameSession session = createSessionWithPlayers(2);
        Long first = session.getPlayers().get(0).getId();
        Long second = session.getPlayers().get(1).getId();
        gameSessionService.startGame(session.getId(), first);
        gameSessionService.chooseClue(session.getId(), first, CLUE_ID);
        clearInvocations(timer, broadcaster);
        gameSessionService.finishRevealingClue(session.getId());

        Runnable staleBuzzerTimeout =
                captureScheduledRunnable(session, Duration.ofSeconds(10));

        gameSessionService.buzz(session.getId(), first);
        gameSessionService.answerClue(session.getId(), first, "Wrong answer");
        assertEquals(GameSessionState.WAITING_FOR_BUZZER, session.getState());
        clearInvocations(timer, broadcaster);

        staleBuzzerTimeout.run();

        assertEquals(GameSessionState.WAITING_FOR_BUZZER, session.getState());
        verifyNoInteractions(timer, broadcaster);

        // The new window still works.
        gameSessionService.buzz(session.getId(), second);
        assertEquals(second, session.getPlayerAnswering());
    }

    @Test
    void currentBuzzerTimeout_afterReturningToWaitingForBuzzer_endsTheClue() {
        GameSession session = createSessionWithPlayers(2);
        Long first = session.getPlayers().get(0).getId();
        gameSessionService.startGame(session.getId(), first);
        gameSessionService.chooseClue(session.getId(), first, CLUE_ID);
        gameSessionService.finishRevealingClue(session.getId());
        gameSessionService.buzz(session.getId(), first);
        clearInvocations(timer);
        gameSessionService.answerClue(session.getId(), first, "Wrong answer");

        captureScheduledRunnable(session, Duration.ofSeconds(10)).run();

        assertEquals(GameSessionState.REVEALING_ANSWER, session.getState());
    }

    @Test
    void timeoutAfterSessionRemoved_doesNothing() {
        // removeSession cancels the timer, but a callback that is already
        // running finds no session.
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
        clearInvocations(timer, broadcaster);

        clueRevealTimeout.run();

        assertEquals(GameSessionState.REVEALING_CLUE, session.getState());
        verifyNoInteractions(timer, broadcaster);
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
                new GameSessionPlayer(1L, "Player 1", PlayerTokens.generate())
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

    // -------------------------------------------------------------------------
    // Final Jeopardy
    // -------------------------------------------------------------------------

    @Test
    void finishingLastClue_startsFinalJeopardyAndSchedulesWagerTimeout() {
        GameSession session = createSessionInFinalJeopardy();

        assertEquals(
                GameSessionState.FINAL_JEOPARDY_WAGERING,
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
    void placeFinalJeopardyWager_broadcastsWithoutTouchingTheTimer() {
        GameSession session = createSessionInFinalJeopardy();

        clearInvocations(timer, broadcaster);

        gameSessionService.placeFinalJeopardyWager(session.getId(), PLAYER_ID, 100);

        assertEquals(100, session.getFinalJeopardy().getWager(PLAYER_ID));
        verify(broadcaster).broadcast(session);
        verifyPhaseTimerUntouched();
    }

    @Test
    void answerFinalJeopardy_broadcastsWithoutTouchingTheTimer() {
        GameSession session = createSessionInFinalJeopardy();
        session.timeoutFinalJeopardyWagering();

        clearInvocations(timer, broadcaster);

        gameSessionService.answerFinalJeopardy(session.getId(), PLAYER_ID, "Final answer");

        assertEquals("Final answer", session.getFinalJeopardy().answerOf(PLAYER_ID).answer());
        verify(broadcaster).broadcast(session);
        verifyPhaseTimerUntouched();
    }

    @Test
    void finalJeopardyTimeouts_runThroughEveryPhaseAndApplyScores() {
        GameSession session = createSessionInFinalJeopardy();
        Runnable wagerTimeout =
                captureScheduledRunnable(session, Duration.ofSeconds(30));

        gameSessionService.placeFinalJeopardyWager(session.getId(), PLAYER_ID, 150);
        clearInvocations(timer, broadcaster);

        wagerTimeout.run();

        assertEquals(GameSessionState.FINAL_JEOPARDY_ANSWERING, session.getState());
        verify(broadcaster).broadcast(session);
        Runnable answerTimeout =
                captureScheduledRunnable(session, Duration.ofSeconds(30));

        gameSessionService.answerFinalJeopardy(session.getId(), PLAYER_ID, "What is the final answer?");
        clearInvocations(timer, broadcaster);

        answerTimeout.run();

        assertEquals(GameSessionState.FINAL_JEOPARDY_REVEALING, session.getState());
        verify(broadcaster).broadcast(session);
        Runnable revealTimeout =
                captureScheduledRunnable(session, Duration.ofSeconds(10));

        clearInvocations(timer, broadcaster);

        revealTimeout.run();

        assertEquals(GameSessionState.FINISHED_GAME, session.getState());
        assertEquals(200 + 150, session.getPlayers().getFirst().getScore());
        verify(broadcaster).broadcast(session);
        verify(timer).cancel(session.getId());
        verify(timer).scheduleCleanup(
                eq(session.getId()),
                eq(GameSessionService.FINISHED_SESSION_TIMEOUT),
                any(Runnable.class)
        );
        verifyNoMoreInteractions(timer);
    }

    @Test
    void staleFinalJeopardyWagerTimeout_doesNothing() {
        GameSession session = createSessionInFinalJeopardy();
        Runnable staleWagerTimeout =
                captureScheduledRunnable(session, Duration.ofSeconds(30));

        session.timeoutFinalJeopardyWagering();
        clearInvocations(timer, broadcaster);

        staleWagerTimeout.run();

        assertEquals(GameSessionState.FINAL_JEOPARDY_ANSWERING, session.getState());
        verifyNoInteractions(timer, broadcaster);
    }

    // Plays the only Jeopardy clue (answered correctly, so the player has 200)
    // and leaves the session at the start of Final Jeopardy, with the timer
    // and broadcaster mocks only holding what entering Final Jeopardy did.
    private GameSession createSessionInFinalJeopardy() {
        GameBoard board = new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(new BoardCategory(
                        "Category",
                        List.of(new BoardClue(CLUE_ID, 1, "Question", "Correct answer", 200, false))
                ))),
                new BoardRound(GameRound.FINAL_JEOPARDY, List.of(new BoardCategory(
                        "Final category",
                        List.of(new BoardClue(FINAL_CLUE_ID, 1, "Final question", "Final answer", 2000, false))
                )))
        ));

        GameSession session = gameSessionService.createSession(board);
        session.addPlayer(new GameSessionPlayer(PLAYER_ID, "Player 1", PlayerTokens.generate()));

        gameSessionService.startGame(session.getId(), PLAYER_ID);
        gameSessionService.chooseClue(session.getId(), PLAYER_ID, CLUE_ID);
        gameSessionService.finishRevealingClue(session.getId());
        gameSessionService.buzz(session.getId(), PLAYER_ID);
        gameSessionService.answerClue(session.getId(), PLAYER_ID, "Correct answer");

        clearInvocations(timer, broadcaster);

        gameSessionService.finishRevealingAnswer(session.getId());

        return session;
    }

    // -------------------------------------------------------------------------
    // Player numbering, version and state reads
    // -------------------------------------------------------------------------

    @Test
    void joinSession_numbersPlayersFromOneInEachSession() {
        GameSession first = createSession();
        GameSession second = createSession();

        assertEquals(1L, gameSessionService.joinSession(first.getId(), "Nico").getId());
        assertEquals(2L, gameSessionService.joinSession(first.getId(), "John").getId());
        assertEquals(1L, gameSessionService.joinSession(second.getId(), "Ana").getId());
    }

    @Test
    void everyBroadcast_increasesTheVersion() {
        GameSession session = createSession();

        assertEquals(0, session.getVersion());

        gameSessionService.joinSession(session.getId(), "Nico");
        assertEquals(1, session.getVersion());

        gameSessionService.startGame(session.getId(), getFirstPlayerId(session));
        assertEquals(2, session.getVersion());
    }

    @Test
    void getSessionState_returnsTheCurrentState() {
        GameSession session = createSession();
        gameSessionService.joinSession(session.getId(), "Nico");

        GameSessionResponse state =
                gameSessionService.getSessionState(session.getId());

        assertEquals(session.getId(), state.id());
        assertEquals(1, state.version());
        assertEquals(GameSessionState.WAITING_FOR_START, state.state());
        assertEquals("Nico", state.players().getFirst().name());
        assertNull(state.phaseDurationMs());
        assertNull(state.phaseRemainingMs());
    }

    // -------------------------------------------------------------------------
    // Phase timing
    // -------------------------------------------------------------------------

    @Test
    void scheduledTimeout_isRecordedAsThePhaseTimer() {
        GameSession session = createStartedSession();

        long before = System.nanoTime();
        gameSessionService.chooseClue(session.getId(), getFirstPlayerId(session), CLUE_ID);

        assertEquals(Duration.ofSeconds(3), session.getPhaseDuration());
        Duration remaining = session.getPhaseRemaining(before);
        assertTrue(remaining.compareTo(Duration.ofSeconds(3)) >= 0, remaining.toString());

        GameSessionResponse state = gameSessionService.getSessionState(session.getId());
        assertEquals(3000L, state.phaseDurationMs());
        assertTrue(state.phaseRemainingMs() > 0 && state.phaseRemainingMs() <= 3000, state.toString());
    }

    @Test
    void finalJeopardySubmissions_keepThePhaseDeadline() {
        GameSession session = createSessionInFinalJeopardy();
        long later = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        Duration remainingBefore = session.getPhaseRemaining(later);

        gameSessionService.placeFinalJeopardyWager(session.getId(), PLAYER_ID, 100);

        assertEquals(Duration.ofSeconds(30), session.getPhaseDuration());
        assertEquals(remainingBefore, session.getPhaseRemaining(later));
    }

    @Test
    void finishingTheGame_clearsThePhaseTimer() {
        GameSession session = createSessionInFinalJeopardy();
        Runnable wagerTimeout =
                captureScheduledRunnable(session, Duration.ofSeconds(30));
        clearInvocations(timer);
        wagerTimeout.run();

        Runnable answerTimeout =
                captureScheduledRunnable(session, Duration.ofSeconds(30));
        clearInvocations(timer);
        answerTimeout.run();

        Runnable revealTimeout =
                captureScheduledRunnable(session, Duration.ofSeconds(10));
        revealTimeout.run();

        assertEquals(GameSessionState.FINISHED_GAME, session.getState());
        assertNull(session.getPhaseDuration());
    }

    @Test
    void getSessionState_throwsWhenSessionDoesNotExist() {
        assertThrows(
                GameSessionNotFoundException.class,
                () -> gameSessionService.getSessionState(SessionCode.random())
        );
    }

    // -------------------------------------------------------------------------
    // Leaving and kicking
    // -------------------------------------------------------------------------

    @Test
    void leave_revokesTheTokenClosesConnectionsAndBroadcasts() {
        GameSession session = createSession();
        GameSessionPlayer host = gameSessionService.joinSession(session.getId(), "Nico");
        GameSessionPlayer other = gameSessionService.joinSession(session.getId(), "John");
        clearInvocations(timer, broadcaster);

        gameSessionService.leave(session.getId(), other.getId());

        assertEquals(List.of(host), session.getPlayers());
        assertEquals(Optional.empty(), playerTokens.find(other.getToken()));
        assertTrue(playerTokens.find(host.getToken()).isPresent());
        verify(broadcaster).broadcast(session);
        verify(connections).close(
                new PlayerPrincipal(session.getId(), other.getId()),
                GameSessionService.LEFT_MESSAGE
        );
        verifyPhaseTimerUntouched();
    }

    @Test
    void kick_revokesTheKickedPlayersToken() {
        GameSession session = createSession();
        GameSessionPlayer host = gameSessionService.joinSession(session.getId(), "Nico");
        GameSessionPlayer other = gameSessionService.joinSession(session.getId(), "John");
        clearInvocations(timer, broadcaster);

        gameSessionService.kick(session.getId(), host.getId(), other.getId());

        assertEquals(List.of(host), session.getPlayers());
        assertEquals(Optional.empty(), playerTokens.find(other.getToken()));
        verify(broadcaster).broadcast(session);
        verify(connections).close(
                new PlayerPrincipal(session.getId(), other.getId()),
                GameSessionService.KICKED_MESSAGE
        );
    }

    @Test
    void kick_byANonHost_changesNothing() {
        GameSession session = createSession();
        GameSessionPlayer host = gameSessionService.joinSession(session.getId(), "Nico");
        GameSessionPlayer other = gameSessionService.joinSession(session.getId(), "John");
        clearInvocations(timer, broadcaster, connections);

        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> gameSessionService.kick(session.getId(), other.getId(), host.getId())
        );

        assertEquals(GameErrorCode.NOT_HOST, exception.getCode());
        assertEquals(2, session.getPlayers().size());
        assertTrue(playerTokens.find(host.getToken()).isPresent());
        verifyNoInteractions(timer, broadcaster, connections);
    }

    @Test
    void hostLeaving_broadcastsTheNewHost() {
        GameSession session = createSession();
        GameSessionPlayer host = gameSessionService.joinSession(session.getId(), "Nico");
        GameSessionPlayer other = gameSessionService.joinSession(session.getId(), "John");

        clearInvocations(broadcaster);
        AtomicBoolean checked = new AtomicBoolean();
        doAnswer(invocation -> {
            GameSessionResponse response = GameSessionResponse.from(invocation.<GameSession>getArgument(0));
            assertEquals(other.getId(), response.hostPlayerId());
            checked.set(true);
            return null;
        }).when(broadcaster).broadcast(session);

        gameSessionService.leave(session.getId(), host.getId());

        verify(broadcaster).broadcast(session);
        assertTrue(checked.get());
    }

    @Test
    void leavingWithoutChangingTheState_keepsThePhaseTimerRunning() {
        GameSession session = createSessionWithPlayers(3);
        gameSessionService.startGame(session.getId(), 1L);
        gameSessionService.chooseClue(session.getId(), 1L, CLUE_ID);
        gameSessionService.finishRevealingClue(session.getId());
        clearInvocations(timer, broadcaster);

        gameSessionService.leave(session.getId(), 3L);

        assertEquals(GameSessionState.WAITING_FOR_BUZZER, session.getState());
        verify(broadcaster).broadcast(session);
        verifyPhaseTimerUntouched();
    }

    @Test
    void leavingThatEndsTheBuzzing_schedulesTheAnswerReveal() {
        GameSession session = createSessionWithPlayers(2);
        gameSessionService.startGame(session.getId(), 1L);
        gameSessionService.chooseClue(session.getId(), 1L, CLUE_ID);
        gameSessionService.finishRevealingClue(session.getId());
        gameSessionService.buzz(session.getId(), 2L);
        gameSessionService.answerClue(session.getId(), 2L, "Wrong answer");
        clearInvocations(timer, broadcaster);

        // The chooser leaves; the only active player has already buzzed.
        gameSessionService.leave(session.getId(), 1L);

        assertEquals(GameSessionState.REVEALING_ANSWER, session.getState());
        verify(timer).schedule(eq(session.getId()), eq(Duration.ofSeconds(3)), any(Runnable.class));
        verify(broadcaster).broadcast(session);
    }

    @Test
    void lastActivePlayerLeaving_closesAndRemovesTheSession() {
        GameSession session = createSession();
        GameSessionPlayer player = gameSessionService.joinSession(session.getId(), "Nico");
        gameSessionService.startGame(session.getId(), player.getId());

        assertClosedOnNextBroadcast(session, SessionCloseReason.ABANDONED);
        clearInvocations(timer);

        gameSessionService.leave(session.getId(), player.getId());

        verify(broadcaster).broadcast(session);
        assertThrows(GameSessionNotFoundException.class, () -> gameSessionService.getSession(session.getId()));
        assertEquals(Optional.empty(), playerTokens.find(player.getToken()));
        verify(timer).cancel(session.getId());
        verify(connections).close(
                new PlayerPrincipal(session.getId(), player.getId()),
                GameSessionService.LEFT_MESSAGE
        );
    }

    @Test
    void everyoneLeavingWhileAChooserIsStillLeaving_closesTheSession() {
        GameSession session = createSession();
        GameSessionPlayer chooser = gameSessionService.joinSession(session.getId(), "Nico");
        GameSessionPlayer other = gameSessionService.joinSession(session.getId(), "John");
        gameSessionService.startGame(session.getId(), chooser.getId());
        gameSessionService.leave(session.getId(), chooser.getId());

        assertTrue(chooser.isLeaving());
        assertClosedOnNextBroadcast(session, SessionCloseReason.ABANDONED);

        gameSessionService.leave(session.getId(), other.getId());

        assertThrows(GameSessionNotFoundException.class, () -> gameSessionService.getSession(session.getId()));
    }

    // -------------------------------------------------------------------------
    // Timers
    // -------------------------------------------------------------------------

    @Test
    void timersSetInTheLobby_areUsedOnceTheGameStarts() {
        GameSession session = createSession();
        GameSessionPlayer player = gameSessionService.joinSession(session.getId(), "Nico");
        gameSessionService.updateTimers(
                session.getId(),
                player.getId(),
                new SessionTimersRequest(45_000L, null, null, null, null, null, null, null, null)
        );

        gameSessionService.startGame(session.getId(), player.getId());

        verify(timer).schedule(eq(session.getId()), eq(Duration.ofSeconds(45)), any(Runnable.class));
    }

    @Test
    void updateTimers_changesOnlyTheGivenDurationsAndBroadcasts() {
        GameSession session = createSession();
        GameSessionPlayer host = gameSessionService.joinSession(session.getId(), "Nico");
        clearInvocations(timer, broadcaster);

        gameSessionService.updateTimers(
                session.getId(),
                host.getId(),
                new SessionTimersRequest(null, null, null, 4_000L, null, null, null, null, null)
        );

        assertEquals(Duration.ofSeconds(4), session.getTimeouts().buzzer());
        assertEquals(GameSessionTimeouts.defaults().clueChoice(), session.getTimeouts().clueChoice());
        verify(broadcaster).broadcast(session);
        verifyPhaseTimerUntouched();
    }

    @Test
    void updateTimers_byANonHost_isRejected() {
        GameSession session = createSession();
        gameSessionService.joinSession(session.getId(), "Nico");
        GameSessionPlayer other = gameSessionService.joinSession(session.getId(), "John");
        clearInvocations(broadcaster);

        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> gameSessionService.updateTimers(
                        session.getId(),
                        other.getId(),
                        new SessionTimersRequest(null, null, null, 4_000L, null, null, null, null, null)
                )
        );

        assertEquals(GameErrorCode.NOT_HOST, exception.getCode());
        assertEquals(GameSessionTimeouts.defaults(), session.getTimeouts());
        verifyNoInteractions(broadcaster);
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    @Test
    void newSession_isRemovedWhenItsLobbyIdlesTooLong() {
        GameSession session = createSession();
        Runnable cleanup = captureCleanup(session, GameSessionService.IDLE_LOBBY_TIMEOUT);

        assertClosedOnNextBroadcast(session, SessionCloseReason.IDLE);

        cleanup.run();

        verify(broadcaster).broadcast(session);
        assertThrows(GameSessionNotFoundException.class, () -> gameSessionService.getSession(session.getId()));
    }

    @Test
    void lobbyCleanup_revokesEveryToken() {
        GameSession session = createSession();
        GameSessionPlayer player = gameSessionService.joinSession(session.getId(), "Nico");
        Runnable cleanup = captureCleanup(session, GameSessionService.IDLE_LOBBY_TIMEOUT);

        cleanup.run();

        assertEquals(Optional.empty(), playerTokens.find(player.getToken()));
        // The connections stay open, so clients see the CLOSED broadcast.
        verify(connections, never()).close(any(), any());
    }

    @Test
    void everyChangeInTheLobby_restartsTheIdleTimeout() {
        GameSession session = createSession();
        Runnable staleCleanup = captureCleanup(session, GameSessionService.IDLE_LOBBY_TIMEOUT);

        gameSessionService.joinSession(session.getId(), "Nico");
        clearInvocations(broadcaster);

        staleCleanup.run();

        assertSame(session, gameSessionService.getSession(session.getId()));
        verifyNoInteractions(broadcaster);
    }

    @Test
    void startingTheGame_cancelsTheIdleTimeout() {
        GameSession session = createSession();
        GameSessionPlayer player = gameSessionService.joinSession(session.getId(), "Nico");
        Runnable staleCleanup = captureCleanup(session, GameSessionService.IDLE_LOBBY_TIMEOUT);

        gameSessionService.startGame(session.getId(), player.getId());

        verify(timer).cancelCleanup(session.getId());

        // Even if it fires anyway, a game in progress is never cleaned up.
        staleCleanup.run();
        assertEquals(GameSessionState.CHOOSING_CLUE, session.getState());
    }

    @Test
    void finishedSession_isRemovedAfterTheFinishedTimeout() {
        GameSession session = gameSessionService.createSession(
                board(new BoardClue(CLUE_ID, 1, "Question", "Correct answer", 200, false))
        );
        GameSessionPlayer player = gameSessionService.joinSession(session.getId(), "Nico");
        gameSessionService.startGame(session.getId(), player.getId());
        gameSessionService.chooseClue(session.getId(), player.getId(), CLUE_ID);
        gameSessionService.finishRevealingClue(session.getId());
        gameSessionService.buzz(session.getId(), player.getId());
        gameSessionService.answerClue(session.getId(), player.getId(), "Correct answer");
        clearInvocations(timer);
        gameSessionService.finishRevealingAnswer(session.getId());
        assertEquals(GameSessionState.FINISHED_GAME, session.getState());

        Runnable cleanup = captureCleanup(session, GameSessionService.FINISHED_SESSION_TIMEOUT);
        assertClosedOnNextBroadcast(session, SessionCloseReason.FINISHED);

        cleanup.run();

        assertThrows(GameSessionNotFoundException.class, () -> gameSessionService.getSession(session.getId()));
        assertEquals(Optional.empty(), playerTokens.find(player.getToken()));
    }

    @Test
    void cleanupOfAnAlreadyRemovedSession_doesNothing() {
        GameSession session = createSession();
        Runnable cleanup = captureCleanup(session, GameSessionService.IDLE_LOBBY_TIMEOUT);
        gameSessionService.removeSession(session.getId());

        assertDoesNotThrow(cleanup::run);
        verifyNoInteractions(broadcaster);
    }

    // -------------------------------------------------------------------------
    // Listing
    // -------------------------------------------------------------------------

    // Filtering, ordering and paging are SessionLobby's (SessionLobbyTest).
    @Test
    void localSummaries_listsEverySessionIncludingFinishedOnes() {
        GameSession waiting = createSession();
        GameSession finished = createSession();
        finished.addPlayer("Nico", PlayerTokens.generate());
        finished.startGame(1L);
        finished.timeoutChoosingClue();
        finished.finishRevealingClue();
        finished.timeoutBuzzer();
        finished.finishRevealingAnswer();
        finished.timeoutChoosingClue();
        finished.finishRevealingClue();
        finished.timeoutBuzzer();
        finished.finishRevealingAnswer();
        assertEquals(GameSessionState.FINISHED_GAME, finished.getState());

        assertEquals(
                Set.of(waiting.getId(), finished.getId()),
                gameSessionService.localSummaries().stream()
                        .map(GameSessionSummaryResponse::id)
                        .collect(Collectors.toSet())
        );
    }

    @Test
    void localSummaries_summarizeTheHostAndActivePlayers() {
        GameBoard board = new GameBoard(42L, "Show #42", createBoard().getRounds());
        GameSession session = gameSessionService.createSession(board);
        GameSessionPlayer host = gameSessionService.joinSession(session.getId(), "Nico");
        gameSessionService.joinSession(session.getId(), "John");
        gameSessionService.joinSession(session.getId(), "Ana");
        gameSessionService.startGame(session.getId(), host.getId());
        // The chooser stays (leaving) until someone else takes control.
        gameSessionService.leave(session.getId(), host.getId());

        GameSessionSummaryResponse summary =
                gameSessionService.localSummaries().getFirst();

        assertEquals(session.getId(), summary.id());
        assertEquals(42L, summary.gameId());
        assertEquals("Show #42", summary.gameTitle());
        assertEquals(GameSessionState.CHOOSING_CLUE, summary.state());
        assertEquals(GameRound.JEOPARDY, summary.round());
        assertEquals(2, summary.playerCount());
        assertEquals("John", summary.hostName());
        assertEquals(Instant.parse("2026-10-09T12:00:00Z"), summary.createdAt());
    }

    // -------------------------------------------------------------------------
    // Helpers for leaving, timers and cleanup
    // -------------------------------------------------------------------------

    private void verifyPhaseTimerUntouched() {
        verify(timer, never()).schedule(any(), any(), any());
        verify(timer, never()).cancel(any());
    }

    // The most recently armed cleanup for the session.
    private Runnable captureCleanup(GameSession session, Duration timeout) {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);

        verify(timer, org.mockito.Mockito.atLeastOnce()).scheduleCleanup(
                eq(session.getId()),
                eq(timeout),
                captor.capture()
        );

        return captor.getValue();
    }

    // Checks the broadcast as it is sent, since the session is removed right after.
    private void assertClosedOnNextBroadcast(GameSession session, SessionCloseReason reason) {
        clearInvocations(broadcaster);

        doAnswer(invocation -> {
            GameSessionResponse response = GameSessionResponse.from(invocation.<GameSession>getArgument(0));
            assertEquals(GameSessionState.CLOSED, response.state());
            assertEquals(reason, response.closeReason());
            assertNull(response.phaseDurationMs());
            assertSame(session, gameSessionService.getSession(session.getId()));
            return null;
        }).when(broadcaster).broadcast(session);
    }
}
