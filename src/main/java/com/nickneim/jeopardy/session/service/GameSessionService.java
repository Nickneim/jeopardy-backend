package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.controller.GameSessionBroadcaster;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionState;
import com.nickneim.jeopardy.session.security.PlayerPrincipal;
import com.nickneim.jeopardy.session.security.PlayerTokenRegistry;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GameSessionService {

    private final Map<UUID, GameSession> sessions =
            new ConcurrentHashMap<>();

    private final GameSessionTimer timer;
    private final GameSessionBroadcaster broadcaster;
    private final PlayerTokenRegistry playerTokens;

    public GameSessionService(
            GameSessionTimer timer,
            GameSessionBroadcaster broadcaster,
            PlayerTokenRegistry playerTokens
    ) {
        this.timer = timer;
        this.broadcaster = broadcaster;
        this.playerTokens = playerTokens;
    }

    public GameSession createSession(GameBoard board) {
        UUID id = UUID.randomUUID();
        GameSession session = new GameSession(id, board);

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

        GameSession session = sessions.remove(sessionId);

        if (session != null) {
            session.getPlayers().forEach(player -> playerTokens.unregister(player.getToken()));
        }
    }

    /**
     * Adds a player and returns them, including their secret token. The
     * caller must hand the token only to the joining player.
     */
    public GameSessionPlayer joinSession(
            UUID sessionId,
            String playerName
    ) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            GameSessionPlayer player = new GameSessionPlayer(
                    generatePlayerId(),
                    playerName
            );

            session.addPlayer(player);
            playerTokens.register(
                    player.getToken(),
                    new PlayerPrincipal(sessionId, player.getId())
            );

            broadcaster.broadcast(session);

            return player;
        }
    }

    /** The player a token belongs to, or empty if it's unknown (or null). */
    public Optional<PlayerPrincipal> findPlayerByToken(String token) {
        return playerTokens.find(token);
    }

    public void startGame(
            UUID sessionId,
            Long playerId
    ) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            session.startGame(playerId);

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

    // Final Jeopardy submissions don't change the state, so they deliberately
    // skip scheduleTimeout: re-arming the timer would restart the phase.
    public void placeFinalJeopardyWager(
            UUID sessionId,
            Long playerId,
            int wager
    ) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            session.placeFinalJeopardyWager(playerId, wager);

            broadcaster.broadcast(session);
        }
    }

    public void answerFinalJeopardy(
            UUID sessionId,
            Long playerId,
            String answer
    ) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            session.answerFinalJeopardy(playerId, answer);

            broadcaster.broadcast(session);
        }
    }

    public void finishRevealingClue(UUID sessionId) {
        GameSession session = getSession(sessionId);
        synchronized (session) {
            if (session.getState() != GameSessionState.REVEALING_CLUE) {
                return;
            }
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

        synchronized (session) {
            if (session.getState() != GameSessionState.REVEALING_ANSWER) {
                return;
            }

            session.finishRevealingAnswer();

            scheduleTimeout(session);
            broadcaster.broadcast(session);
        }
    }

    private void timeoutBuzzer(UUID sessionId) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            if (session.getState() != GameSessionState.WAITING_FOR_BUZZER) {
                return;
            }

            session.timeoutBuzzer();

            scheduleTimeout(session);
            broadcaster.broadcast(session);
        }
    }

    private void timeoutAnswer(UUID sessionId) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            if (session.getState() != GameSessionState.WAITING_FOR_ANSWER) {
                return;
            }

            session.timeoutAnswer();

            scheduleTimeout(session);
            broadcaster.broadcast(session);
        }
    }

    private void timeoutDailyDoubleWager(UUID sessionId) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            if (session.getState() != GameSessionState.WAITING_FOR_DAILY_DOUBLE_WAGER) {
                return;
            }

            session.timeoutDailyDoubleWager();

            scheduleTimeout(session);
            broadcaster.broadcast(session);
        }
    }

    private void timeoutFinalJeopardyWagering(UUID sessionId) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            if (session.getState() != GameSessionState.FINAL_JEOPARDY_WAGERING) {
                return;
            }

            session.timeoutFinalJeopardyWagering();

            scheduleTimeout(session);
            broadcaster.broadcast(session);
        }
    }

    private void timeoutFinalJeopardyAnswering(UUID sessionId) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            if (session.getState() != GameSessionState.FINAL_JEOPARDY_ANSWERING) {
                return;
            }

            session.timeoutFinalJeopardyAnswering();

            scheduleTimeout(session);
            broadcaster.broadcast(session);
        }
    }

    private void finishFinalJeopardyReveal(UUID sessionId) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            if (session.getState() != GameSessionState.FINAL_JEOPARDY_REVEALING) {
                return;
            }

            session.finishFinalJeopardyReveal();

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

            case FINAL_JEOPARDY_WAGERING ->
                    timer.schedule(
                            sessionId,
                            session.getTimeouts().finalJeopardyWager(),
                            () -> timeoutFinalJeopardyWagering(sessionId)
                    );

            case FINAL_JEOPARDY_ANSWERING ->
                    timer.schedule(
                            sessionId,
                            session.getTimeouts().finalJeopardyAnswer(),
                            () -> timeoutFinalJeopardyAnswering(sessionId)
                    );

            case FINAL_JEOPARDY_REVEALING ->
                    timer.schedule(
                            sessionId,
                            session.getTimeouts().finalJeopardyReveal(),
                            () -> finishFinalJeopardyReveal(sessionId)
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
