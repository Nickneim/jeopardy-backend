package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GameSessionServiceTest {

    private GameSessionService gameSessionService;

    @BeforeEach
    void setUp() {
        gameSessionService = new GameSessionService();
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
    void removeSession_removesSession() {
        Game game = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "40"
        );
        GameSession session = gameSessionService.createSession(game);

        gameSessionService.removeSession(session.getId());

        assertThrows(
                GameSessionNotFoundException.class,
                () -> gameSessionService.getSession(session.getId())
        );
    }

    @Test
    void createSession_startsInJeopardyChoosingClueState() {
        Game game = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "40"
        );
        GameSession session = gameSessionService.createSession(game);

        assertEquals(
                GameRound.JEOPARDY,
                session.getRound()
        );

        assertEquals(
                GameSessionState.CHOOSING_CLUE,
                session.getState()
        );
    }
}
