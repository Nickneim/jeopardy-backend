package com.nickneim.jeopardy.session;

import com.nickneim.jeopardy.entity.GameRound;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        GameSession session = gameSessionService.createSession();

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
        GameSession session = gameSessionService.createSession();

        gameSessionService.removeSession(session.getId());

        assertThrows(
                GameSessionNotFoundException.class,
                () -> gameSessionService.getSession(session.getId())
        );
    }

    @Test
    void createSession_startsInJeopardyChoosingClueState() {
        GameSession session = gameSessionService.createSession();

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
