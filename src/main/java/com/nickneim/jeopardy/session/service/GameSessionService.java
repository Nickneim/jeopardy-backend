package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.session.controller.GameSessionBroadcaster;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionState;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GameSessionService {

    private final Map<UUID, GameSession> sessions =
            new ConcurrentHashMap<>();

    private final GameSessionTimer timer;
    private final GameSessionBroadcaster broadcaster;

    public GameSessionService(
            GameSessionTimer timer,
            GameSessionBroadcaster broadcaster
    ) {
        this.timer = timer;
        this.broadcaster = broadcaster;
    }

    public GameSession createSession(Game game) {
        UUID id = UUID.randomUUID();
        GameSession session = new GameSession(id, game);

        sessions.put(session.getId(), session);

        return session;
    }

    public GameSession getSession(UUID sessionId) {
        GameSession session = sessions.get(sessionId);

        if (session == null) {
            throw new GameSessionNotFoundException(sessionId);
        }

        return session;
    }

    public void removeSession(UUID sessionId) {
        timer.cancel(sessionId);
        sessions.remove(sessionId);
    }

    public void joinSession(
            UUID sessionId,
            String playerName
    ) {
        GameSession session = getSession(sessionId);

        synchronized (session) {

            session.addPlayer(
                    new GameSessionPlayer(
                            generatePlayerId(),
                            playerName
                    )
            );
        }

        broadcaster.broadcast(session);
    }

    public void startGame(UUID sessionId) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            session.startGame();

            scheduleTimeout(session);
            broadcaster.broadcast(session);
        }
    }

    public void chooseClue(
            UUID sessionId,
            Long playerId,
            Long clueId
    ) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            session.chooseClue(playerId, clueId);

            scheduleTimeout(session);
            broadcaster.broadcast(session);
        }
    }

    public void placeDailyDoubleWager(
            UUID sessionId,
            Long playerId,
            int wager
    ) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            session.placeDailyDoubleWager(playerId, wager);

            scheduleTimeout(session);
            broadcaster.broadcast(session);
        }
    }

    public void finishRevealingClue(UUID sessionId) {
        GameSession session = getSession(sessionId);

        if (session.getState() != GameSessionState.REVEALING_CLUE) {
            return;
        }

        synchronized (session) {
            session.finishRevealingClue();

            scheduleTimeout(session);
            broadcaster.broadcast(session);
        }
    }

    public void buzz(
            UUID sessionId,
            Long playerId
    ) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            session.buzz(playerId);

            scheduleTimeout(session);
            broadcaster.broadcast(session);
        }
    }

    public void answerClue(
            UUID sessionId,
            Long playerId,
            String answer
    ) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            session.answerClue(playerId, answer);

            scheduleTimeout(session);
            broadcaster.broadcast(session);
        }
    }

    public void finishRevealingAnswer(UUID sessionId) {
        GameSession session = getSession(sessionId);

        if (session.getState() != GameSessionState.REVEALING_ANSWER) {
            return;
        }

        synchronized (session) {
            session.finishRevealingAnswer();

            scheduleTimeout(session);
            broadcaster.broadcast(session);
        }
    }

    private void timeoutBuzzer(UUID sessionId) {
        GameSession session = getSession(sessionId);

        if (session.getState() != GameSessionState.WAITING_FOR_BUZZER) {
            return;
        }

        synchronized (session) {
            session.timeoutBuzzer();

            scheduleTimeout(session);
            broadcaster.broadcast(session);
        }
    }

    private void timeoutAnswer(UUID sessionId) {
        GameSession session = getSession(sessionId);

        if (session.getState() != GameSessionState.WAITING_FOR_ANSWER) {
            return;
        }


        synchronized (session) {
            session.timeoutAnswer();

            scheduleTimeout(session);
            broadcaster.broadcast(session);
        }
    }

    private void timeoutDailyDoubleWager(UUID sessionId) {
        GameSession session = getSession(sessionId);

        if (session.getState() != GameSessionState.WAITING_FOR_DAILY_DOUBLE_WAGER) {
            return;
        }


        synchronized (session) {
            session.timeoutDailyDoubleWager();

            scheduleTimeout(session);
            broadcaster.broadcast(session);
        }
    }

    private void scheduleTimeout(GameSession session) {
        UUID sessionId = session.getId();

        timer.cancel(sessionId);

        switch (session.getState()) {
            case REVEALING_CLUE ->
                    timer.schedule(
                            sessionId,
                            session.getTimeouts().clueReveal(),
                            () -> finishRevealingClue(sessionId)
                    );

            case WAITING_FOR_BUZZER ->
                    timer.schedule(
                            sessionId,
                            session.getTimeouts().buzzer(),
                            () -> timeoutBuzzer(sessionId)
                    );

            case WAITING_FOR_ANSWER ->
                    timer.schedule(
                            sessionId,
                            session.getTimeouts().answer(),
                            () -> timeoutAnswer(sessionId)
                    );

            case REVEALING_ANSWER ->
                    timer.schedule(
                            sessionId,
                            session.getTimeouts().answerReveal(),
                            () -> finishRevealingAnswer(sessionId)
                    );
            case WAITING_FOR_DAILY_DOUBLE_WAGER ->
                    timer.schedule(
                            session.getId(),
                            session.getTimeouts().dailyDoubleWager(),
                            () -> timeoutDailyDoubleWager(session.getId())
                    );

            default -> {
                // No timeout for this state.
            }
        }
    }

    private Long generatePlayerId() {
        return System.nanoTime();
    }
}
