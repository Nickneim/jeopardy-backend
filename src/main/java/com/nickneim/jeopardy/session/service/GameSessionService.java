package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.game.dto.PageResponse;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.controller.GameSessionBroadcaster;
import com.nickneim.jeopardy.session.dto.GameSessionResponse;
import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import com.nickneim.jeopardy.session.dto.SessionSettingsRequest;
import com.nickneim.jeopardy.session.dto.SessionTimersRequest;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionState;
import com.nickneim.jeopardy.session.model.GameSessionTimeouts;
import com.nickneim.jeopardy.session.model.SessionCloseReason;
import com.nickneim.jeopardy.session.security.PlayerConnections;
import com.nickneim.jeopardy.session.security.PlayerPrincipal;
import com.nickneim.jeopardy.session.security.PlayerTokenRegistry;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GameSessionService {

    /** A lobby (WAITING_FOR_START) with no change for this long is removed. */
    public static final Duration IDLE_LOBBY_TIMEOUT = Duration.ofMinutes(30);

    /** A finished game with no change for this long is removed. */
    public static final Duration FINISHED_SESSION_TIMEOUT = Duration.ofMinutes(10);

    static final String LEFT_MESSAGE = "You left the session";
    static final String KICKED_MESSAGE = "You were removed from the session by the host";

    private final Map<UUID, GameSession> sessions =
            new ConcurrentHashMap<>();

    private final GameSessionTimer timer;
    private final GameSessionBroadcaster broadcaster;
    private final PlayerTokenRegistry playerTokens;
    private final PlayerConnections connections;
    private final Clock clock;

    public GameSessionService(
            GameSessionTimer timer,
            GameSessionBroadcaster broadcaster,
            PlayerTokenRegistry playerTokens,
            PlayerConnections connections,
            Clock clock
    ) {
        this.timer = timer;
        this.broadcaster = broadcaster;
        this.playerTokens = playerTokens;
        this.connections = connections;
        this.clock = clock;
    }

    /** A new session with the default timers; the host can change them in the lobby. */
    public GameSession createSession(GameBoard board) {
        UUID id = UUID.randomUUID();
        GameSession session = new GameSession(id, board, GameSessionTimeouts.defaults(), clock.instant());

        sessions.put(session.getId(), session);

        // Nobody may ever join, so the idle lobby timeout starts now.
        synchronized (session) {
            scheduleCleanup(session);
        }

        return session;
    }

    /**
     * The open sessions for a lobby page, newest first (ties by id), without
     * finished ones unless {@code includeFinished}. Each summary is built
     * under its session's lock.
     */
    public PageResponse<GameSessionSummaryResponse> listSessions(
            boolean includeFinished,
            int page,
            int size
    ) {
        List<GameSessionSummaryResponse> matching = sessions.values()
                .stream()
                .map(session -> {
                    synchronized (session) {
                        return GameSessionSummaryResponse.from(session);
                    }
                })
                .filter(summary -> summary.state() != GameSessionState.CLOSED)
                .filter(summary -> includeFinished || summary.state() != GameSessionState.FINISHED_GAME)
                .sorted(Comparator.comparing(GameSessionSummaryResponse::createdAt)
                        .reversed()
                        .thenComparing(GameSessionSummaryResponse::id))
                .toList();

        int from = (int) Math.min((long) page * size, matching.size());
        int to = Math.min(from + size, matching.size());

        return new PageResponse<>(
                matching.subList(from, to),
                page,
                size,
                matching.size(),
                (matching.size() + size - 1) / size
        );
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

    /** Forgets a session at once, without a broadcast. */
    public void removeSession(UUID sessionId) {
        timer.cancel(sessionId);
        timer.cancelCleanup(sessionId);

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
            // Closed while this request waited for the lock.
            if (session.getState() == GameSessionState.CLOSED) {
                throw new GameSessionNotFoundException(sessionId);
            }

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

    /**
     * The host changes the phase durations before the game starts. Any
     * duration the request leaves out keeps its current value. There is no
     * timer to re-arm, since the lobby has none.
     */
    public void updateTimers(
            UUID sessionId,
            Long playerId,
            SessionTimersRequest request
    ) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            session.updateTimeouts(playerId, request.applyTo(session.getTimeouts()));

            broadcast(session);
        }
    }

    /** The host's lobby settings. Like the timers, they have no timer to re-arm. */
    public void updateSettings(
            UUID sessionId,
            Long playerId,
            SessionSettingsRequest request
    ) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            session.updateLateJoin(playerId, request.allowLateJoin());

            broadcast(session);
        }
    }

    public void leave(
            UUID sessionId,
            Long playerId
    ) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            GameSessionState before = session.getState();
            GameSessionPlayer player = session.leave(playerId);

            afterDeparture(session, player, before, LEFT_MESSAGE);
        }
    }

    public void kick(
            UUID sessionId,
            Long hostId,
            Long playerId
    ) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            GameSessionState before = session.getState();
            GameSessionPlayer player = session.kick(hostId, playerId);

            afterDeparture(session, player, before, KICKED_MESSAGE);
        }
    }

    /**
     * The player's token stops working at once and their open connections
     * are closed, so a client that reconnects is rejected like any unknown
     * token. If nobody active is left, the session is closed.
     */
    private void afterDeparture(
            GameSession session,
            GameSessionPlayer player,
            GameSessionState before,
            String message
    ) {
        playerTokens.unregister(player.getToken());

        if (session.getActivePlayers().isEmpty()) {
            closeSession(session, SessionCloseReason.ABANDONED);
        } else {
            // A departure usually leaves the state alone, and re-arming the
            // timer then would restart the phase. It changes it when the last
            // player who could still buzz leaves.
            if (session.getState() != before) {
                scheduleTimeout(session);
            }

            broadcast(session);
        }

        connections.close(new PlayerPrincipal(session.getId(), player.getId()), message);
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
        scheduleCleanup(session);
    }

    /**
     * Arms the session's cleanup timer after every change: a lobby or a
     * finished game is removed once it has gone without changes for its
     * timeout. A game in progress always moves on by itself, so it has none.
     * Called with the session's lock held.
     */
    private void scheduleCleanup(GameSession session) {
        UUID sessionId = session.getId();

        Duration timeout = switch (session.getState()) {
            case WAITING_FOR_START -> IDLE_LOBBY_TIMEOUT;
            case FINISHED_GAME -> FINISHED_SESSION_TIMEOUT;
            default -> null;
        };

        if (timeout == null) {
            timer.cancelCleanup(sessionId);
            return;
        }

        long version = session.getVersion();
        timer.scheduleCleanup(sessionId, timeout, () -> expireSession(sessionId, version));
    }

    // A stale cleanup (the session changed since it was armed) is a no-op.
    private void expireSession(UUID sessionId, long version) {
        GameSession session = sessions.get(sessionId);

        if (session == null) {
            return;
        }

        synchronized (session) {
            if (session.getVersion() != version) {
                return;
            }

            switch (session.getState()) {
                case WAITING_FOR_START -> closeSession(session, SessionCloseReason.IDLE);
                case FINISHED_GAME -> closeSession(session, SessionCloseReason.FINISHED);
                default -> {
                }
            }
        }
    }

    /**
     * Sends a last broadcast with state CLOSED, then forgets the session and
     * every token in it. Connections stay open so clients can show the
     * closed session; a reconnect is rejected because the token is gone, and
     * GET /api/game-sessions/{id} returns 404. Called with the lock held.
     */
    private void closeSession(GameSession session, SessionCloseReason reason) {
        UUID sessionId = session.getId();

        timer.cancel(sessionId);
        session.close(reason);
        broadcast(session);

        sessions.remove(sessionId);
        session.getPlayers().forEach(player -> playerTokens.unregister(player.getToken()));
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
            // No timeout for these states.
            case WAITING_FOR_START, FINISHED_GAME, CLOSED -> null;
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
