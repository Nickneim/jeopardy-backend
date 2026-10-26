package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.SessionCode;
import com.nickneim.jeopardy.session.security.PlayerPrincipal;

/**
 * GameSessionService's test-only hooks, for tests outside its package. The
 * application never uses them, so they aren't public on the service.
 */
public final class TestGameSessions {

    private TestGameSessions() {
    }

    /** A new session with a random id. */
    public static GameSession createSession(GameSessionService service, GameBoard board) {
        return service.createSession(board);
    }

    /** See {@link GameSessionService#removeSession}. */
    public static void removeSession(GameSessionService service, SessionCode sessionId) {
        service.removeSession(sessionId);
    }

    /** Ends the clue reveal now instead of on its timer. */
    public static void finishRevealingClue(GameSessionService service, SessionCode sessionId) {
        service.finishRevealingClue(sessionId);
    }

    /** Ends the answer reveal now instead of on its timer. */
    public static void finishRevealingAnswer(GameSessionService service, SessionCode sessionId) {
        service.finishRevealingAnswer(sessionId);
    }

    /**
     * The principal a player's connection would carry: for the session that
     * has the id now, or, if none has, one that belongs to no session.
     */
    public static PlayerPrincipal player(GameSessionService service, SessionCode sessionId, Long playerId) {
        try {
            return PlayerPrincipal.of(service.getSession(sessionId), playerId);
        } catch (GameSessionNotFoundException exception) {
            return new PlayerPrincipal(sessionId, 0, playerId);
        }
    }
}
