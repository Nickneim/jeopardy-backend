package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.controller.GameSessionBroadcaster;
import com.nickneim.jeopardy.session.dto.GameSessionResponse;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionState;
import com.nickneim.jeopardy.session.model.GameSessionTimeouts;
import com.nickneim.jeopardy.session.security.PlayerPrincipal;
import com.nickneim.jeopardy.session.security.PlayerTokenRegistry;
import org.springframework.stereotype.Service;

import java.time.Duration;
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

    /**
     * The current state, exactly as a broadcast would show it. Built under
     * the session's lock so it is never a half-applied change.
     */
    public GameSessionResponse getSessionState(UUID sessionId) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            return GameSessionResponse.from(session);
        }
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
            GameSessionPlayer player = session.addPlayer(playerName);
            playerTokens.register(
                    player.getToken(),
                    new PlayerPrincipal(sessionId, player.getId())
            );

            broadcast(session);

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
            broadcast(session);
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
            broadcast(session);
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
            broadcast(session);
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

            broadcast(session);
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

            broadcast(session);
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
            broadcast(session);
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
            broadcast(session);
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
            broadcast(session);
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
            broadcast(session);
        }
    }

    private void timeoutChoosingClue(UUID sessionId) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            if (session.getState() != GameSessionState.CHOOSING_CLUE) {
                return;
            }

            session.timeoutChoosingClue();

            scheduleTimeout(session);
            broadcast(session);
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
            broadcast(session);
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
            broadcast(session);
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
            broadcast(session);
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
            broadcast(session);
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
            broadcast(session);
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
            broadcast(session);
        }
    }

    // Every state change is broadcast through here, so the version can't be
    // forgotten. Always called while holding the session's lock.
    private void broadcast(GameSession session) {
        session.incrementVersion();
        broadcaster.broadcast(session);
    }

    private void scheduleTimeout(GameSession session) {
        UUID sessionId = session.getId();
        GameSessionTimeouts timeouts = session.getTimeouts();

        timer.cancel(sessionId);

        PhaseTimeout timeout = switch (session.getState()) {
            case CHOOSING_CLUE -> new PhaseTimeout(
                    timeouts.clueChoice(),
                    () -> timeoutChoosingClue(sessionId)
            );
            case REVEALING_CLUE -> new PhaseTimeout(
                    timeouts.clueReveal(),
                    () -> finishRevealingClue(sessionId)
            );
            case WAITING_FOR_BUZZER -> new PhaseTimeout(
                    timeouts.buzzer(),
                    () -> timeoutBuzzer(sessionId)
            );
            case WAITING_FOR_ANSWER -> new PhaseTimeout(
                    timeouts.answer(),
                    () -> timeoutAnswer(sessionId)
            );
            case REVEALING_ANSWER -> new PhaseTimeout(
                    timeouts.answerReveal(),
                    () -> finishRevealingAnswer(sessionId)
            );
            case WAITING_FOR_DAILY_DOUBLE_WAGER -> new PhaseTimeout(
                    timeouts.dailyDoubleWager(),
                    () -> timeoutDailyDoubleWager(sessionId)
            );
            case FINAL_JEOPARDY_WAGERING -> new PhaseTimeout(
                    timeouts.finalJeopardyWager(),
                    () -> timeoutFinalJeopardyWagering(sessionId)
            );
            case FINAL_JEOPARDY_ANSWERING -> new PhaseTimeout(
                    timeouts.finalJeopardyAnswer(),
                    () -> timeoutFinalJeopardyAnswering(sessionId)
            );
            case FINAL_JEOPARDY_REVEALING -> new PhaseTimeout(
                    timeouts.finalJeopardyReveal(),
                    () -> finishFinalJeopardyReveal(sessionId)
            );
            // No timeout for this state.
            case WAITING_FOR_START, FINISHED_GAME -> null;
        };

        if (timeout == null) {
            session.clearPhaseTimer();
            return;
        }

        // Recorded on the session so every response can say how long the
        // phase has left.
        session.startPhaseTimer(timeout.duration(), System.nanoTime());
        timer.schedule(sessionId, timeout.duration(), timeout.action());
    }

    private record PhaseTimeout(Duration duration, Runnable action) {}
}
