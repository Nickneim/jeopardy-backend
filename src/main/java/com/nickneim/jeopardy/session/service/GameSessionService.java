package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionState;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import com.nickneim.jeopardy.session.model.GameSessionTimeouts;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GameSessionService {

    private final Map<UUID, GameSession> sessions =
            new ConcurrentHashMap<>();

    private final GameSessionTimer timer;

    public GameSessionService(GameSessionTimer timer) {
        this.timer = timer;
    }

    public GameSession createSession(Game game) {
        UUID id = UUID.randomUUID();

        GameSession session = new GameSession(id, game);

        sessions.put(id, session);

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
        timer.cancel(id);
        sessions.remove(id);
    }

    public void startGame(UUID sessionId) {
        GameSession session = getSession(sessionId);

        session.startGame();

        scheduleTimeout(session);
    }

    public void chooseClue(
            UUID sessionId,
            Long playerId,
            Long clueId
    ) {
        GameSession session = getSession(sessionId);

        session.chooseClue(playerId, clueId);

        scheduleTimeout(session);
    }

    public void finishRevealingClue(UUID sessionId) {
        GameSession session = getSession(sessionId);

        session.finishRevealingClue();

        scheduleTimeout(session);
    }

    public void buzz(
            UUID sessionId,
            Long playerId
    ) {
        GameSession session = getSession(sessionId);

        session.buzz(playerId);

        scheduleTimeout(session);
    }

    public void answerClue(
            UUID sessionId,
            Long playerId,
            String answer
    ) {
        GameSession session = getSession(sessionId);

        session.answerClue(playerId, answer);

        scheduleTimeout(session);
    }

    public void finishRevealingAnswer(UUID sessionId) {
        GameSession session = getSession(sessionId);

        session.finishRevealingAnswer();

        scheduleTimeout(session);
    }

    private void timeoutBuzzer(UUID sessionId) {
        GameSession session = getSession(sessionId);

        if (session.getState() != GameSessionState.WAITING_FOR_BUZZER) {
            return;
        }

        session.timeoutBuzzer();

        scheduleTimeout(session);
    }

    private void timeoutAnswer(UUID sessionId) {
        GameSession session = getSession(sessionId);

        if (session.getState() != GameSessionState.WAITING_FOR_ANSWER) {
            return;
        }

        session.timeoutAnswer();

        scheduleTimeout(session);
    }

    private void scheduleTimeout(GameSession session) {
        UUID sessionId = session.getId();
        GameSessionTimeouts timeouts = session.getTimeouts();

        timer.cancel(sessionId);

        switch (session.getState()) {
            case REVEALING_CLUE ->
                    timer.schedule(
                            sessionId,
                            timeouts.clueReveal(),
                            () -> finishRevealingClue(sessionId)
                    );

            case WAITING_FOR_BUZZER ->
                    timer.schedule(
                            sessionId,
                            timeouts.buzzer(),
                            () -> timeoutBuzzer(sessionId)
                    );

            case WAITING_FOR_ANSWER ->
                    timer.schedule(
                            sessionId,
                            timeouts.answer(),
                            () -> timeoutAnswer(sessionId)
                    );

            case REVEALING_ANSWER ->
                    timer.schedule(
                            sessionId,
                            timeouts.answerReveal(),
                            () -> finishRevealingAnswer(sessionId)
                    );
        }
    }
}
