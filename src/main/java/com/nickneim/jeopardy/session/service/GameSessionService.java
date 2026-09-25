package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GameSessionService {

    private final Map<UUID, GameSession> sessions =
            new ConcurrentHashMap<>();

    public GameSession createSession(Game game) {
        UUID sessionId = UUID.randomUUID();

        GameSession session = new GameSession(sessionId, game);
        sessions.put(sessionId, session);

        return session;
    }

    public GameSession getSession(UUID id) {
        GameSession session = sessions.get(id);

        if (session == null) {
            throw new GameSessionNotFoundException(id);
        }

        return session;
    }

    public void removeSession(UUID id) {
        sessions.remove(id);
    }
}
