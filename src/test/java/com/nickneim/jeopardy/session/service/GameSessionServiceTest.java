package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.game.entity.Category;
import com.nickneim.jeopardy.game.entity.Clue;
import com.nickneim.jeopardy.game.entity.ClueType;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.session.controller.GameSessionBroadcaster;
import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class GameSessionServiceTest {

    private GameSessionService gameSessionService;
    private GameSessionTimer timer;
    private GameSessionBroadcaster broadcaster;

    private static final Long CLUE_ID = 10L;

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
        Game game = createGame();

        GameSession session = gameSessionService.createSession(game);

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

        gameSessionService.startGame(session.getId());

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
        return gameSessionService.createSession(createGame());
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

        gameSessionService.startGame(session.getId());

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

        gameSessionService.startGame(session.getId());

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

    private Game createGame() {
        return createGameWithClue();
    }

    private Game createGameWithClue() {
        Game game = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "40"
        );

        Category category = new Category(
                "Category",
                (short) 1,
                null,
                game
        );

        Clue clue = new Clue(
                "Question",
                "Correct answer",
                (short) 1,
                ClueType.TEXT,
                null,
                category
        );

        ReflectionTestUtils.setField(game, "id", 123L);
        ReflectionTestUtils.setField(category, "id", 456L);
        ReflectionTestUtils.setField(clue, "id", CLUE_ID);

        game.getCategories().add(category);
        category.getClues().add(clue);

        return game;
    }

    @Test
    void concurrentBuzzes_onlyOnePlayerCanBuzz() throws Exception {
        GameSession session = createSessionWithPlayers(2);

        gameSessionService.startGame(session.getId());

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
}
