package com.nickneim.jeopardy.session.service;


import com.nickneim.jeopardy.game.entity.*;
import com.nickneim.jeopardy.session.controller.GameSessionBroadcaster;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GameSessionServiceTest {

    private GameSessionService gameSessionService;
    private GameSessionTimer timer;
    private GameSessionBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        timer = mock(GameSessionTimer.class);
        broadcaster = mock(GameSessionBroadcaster.class);
        gameSessionService = new GameSessionService(timer, broadcaster);
    }

    @Test
    void createSession_createsAndStoresSession() {
        Game game = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "40"
        );

        GameSession session = gameSessionService.createSession(game);

        assertNotNull(session);
        assertNotNull(session.getId());

        assertEquals(
                session,
                gameSessionService.getSession(session.getId())
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
    void createSession_startsInWaitingForStartState() {
        Game game = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "40"
        );

        GameSession session = gameSessionService.createSession(game);

        assertEquals(
                GameSessionState.WAITING_FOR_START,
                session.getState()
        );
    }

    @Test
    void chooseClue_schedulesRevealClueTimeout() {
        Long playerId = 1L;
        Long clueId = 10L;

        Game game = createGameWithClue(clueId);
        GameSession session = gameSessionService.createSession(game);

        session.addPlayer(new GameSessionPlayer(playerId, "Player 1"));
        gameSessionService.startGame(session.getId());

        clearInvocations(timer);
        clearInvocations(broadcaster);
        gameSessionService.chooseClue(
                session.getId(),
                playerId,
                clueId
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
    void finishRevealingClue_schedulesBuzzerTimeout() {
        Long playerId = 1L;
        Long clueId = 10L;

        Game game = createGameWithClue(clueId);
        GameSession session = gameSessionService.createSession(game);

        session.addPlayer(new GameSessionPlayer(playerId, "Player 1"));
        gameSessionService.startGame(session.getId());

        gameSessionService.chooseClue(
                session.getId(),
                playerId,
                clueId
        );

        clearInvocations(timer);
        clearInvocations(broadcaster);
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

        verify(broadcaster).broadcast(session);
    }

    @Test
    void buzz_schedulesAnswerTimeout() {
        Long playerId = 1L;
        Long clueId = 10L;

        Game game = createGameWithClue(clueId);
        GameSession session = gameSessionService.createSession(game);

        session.addPlayer(new GameSessionPlayer(playerId, "Player 1"));
        gameSessionService.startGame(session.getId());

        gameSessionService.chooseClue(
                session.getId(),
                playerId,
                clueId
        );

        gameSessionService.finishRevealingClue(session.getId());

        clearInvocations(timer);
        clearInvocations(broadcaster);
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
        verify(broadcaster).broadcast(session);
    }

    @Test
    void answerClue_correctAnswer_schedulesAnswerReveal() {
        Long playerId = 1L;
        Long clueId = 10L;

        Game game = createGameWithClue(clueId);
        GameSession session = gameSessionService.createSession(game);

        session.addPlayer(new GameSessionPlayer(playerId, "Player 1"));
        gameSessionService.startGame(session.getId());

        gameSessionService.chooseClue(
                session.getId(),
                playerId,
                clueId
        );

        gameSessionService.finishRevealingClue(session.getId());

        gameSessionService.buzz(
                session.getId(),
                playerId
        );

        clearInvocations(timer);
        clearInvocations(broadcaster);
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
        verify(broadcaster).broadcast(session);
    }

    @Test
    void answerClue_incorrectAnswer_schedulesNextBuzzerTimeout() {
        Long player1Id = 1L;
        Long player2Id = 2L;
        Long clueId = 10L;

        Game game = createGameWithClue(clueId);
        GameSession session = gameSessionService.createSession(game);

        session.addPlayer(new GameSessionPlayer(player1Id, "Player 1"));
        session.addPlayer(new GameSessionPlayer(player2Id, "Player 2"));

        gameSessionService.startGame(session.getId());

        gameSessionService.chooseClue(
                session.getId(),
                player1Id,
                clueId
        );

        gameSessionService.finishRevealingClue(session.getId());

        gameSessionService.buzz(
                session.getId(),
                player1Id
        );

        clearInvocations(timer);
        clearInvocations(broadcaster);
        gameSessionService.answerClue(
                session.getId(),
                player1Id,
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
        verify(broadcaster).broadcast(session);
    }

    @Test
    void finishRevealingAnswer_doesNotScheduleAnotherTimeout() {
        Long playerId = 1L;
        Long clueId = 10L;

        Game game = createGameWithClue(clueId);
        GameSession session = gameSessionService.createSession(game);

        session.addPlayer(new GameSessionPlayer(playerId, "Player 1"));

        gameSessionService.startGame(session.getId());

        gameSessionService.chooseClue(
                session.getId(),
                playerId,
                clueId
        );

        gameSessionService.finishRevealingClue(session.getId());

        gameSessionService.buzz(
                session.getId(),
                playerId
        );

        gameSessionService.answerClue(
                session.getId(),
                playerId,
                "Correct answer"
        );

        clearInvocations(timer);
        clearInvocations(broadcaster);
        gameSessionService.finishRevealingAnswer(session.getId());

        assertEquals(
                GameSessionState.CHOOSING_CLUE,
                session.getState()
        );

        verify(timer).cancel(session.getId());

        verifyNoMoreInteractions(timer);
        verify(broadcaster).broadcast(session);
    }

    @Test
    void removeSession_removesSession() {
        Game game = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "40"
        );

        GameSession session = gameSessionService.createSession(game);


        clearInvocations(timer);
        clearInvocations(broadcaster);
        gameSessionService.removeSession(session.getId());

        assertThrows(
                GameSessionNotFoundException.class,
                () -> gameSessionService.getSession(session.getId())
        );

        verify(timer).cancel(session.getId());
        verifyNoInteractions(broadcaster);
    }

    private Game createGameWithClue(Long clueId) {
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
        ReflectionTestUtils.setField(clue, "id", clueId);

        game.getCategories().add(category);
        category.getClues().add(clue);

        return game;
    }

}

