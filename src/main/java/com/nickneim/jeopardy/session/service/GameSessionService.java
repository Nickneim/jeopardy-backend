package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.controller.GameSessionBroadcaster;
import com.nickneim.jeopardy.session.controller.SessionWatchers;
import com.nickneim.jeopardy.session.dto.GameSessionResponse;
import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import com.nickneim.jeopardy.session.dto.SessionSettingsRequest;
import com.nickneim.jeopardy.session.dto.SessionTimersRequest;
import com.nickneim.jeopardy.session.exception.GameSessionAlreadyExistsException;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionState;
import com.nickneim.jeopardy.session.model.GameSessionTimeouts;
import com.nickneim.jeopardy.session.model.SessionCloseReason;
import com.nickneim.jeopardy.session.security.PlayerConnections;
import com.nickneim.jeopardy.session.security.PlayerPrincipal;
import com.nickneim.jeopardy.session.security.PlayerTokenRegistry;
import com.nickneim.jeopardy.session.security.PlayerTokens;
import com.nickneim.jeopardy.session.model.SessionCode;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
public class GameSessionService {

    /** A lobby (WAITING_FOR_START) with no change for this long is removed. */
    public static final Duration IDLE_LOBBY_TIMEOUT = Duration.ofMinutes(30);

    /** A finished game with no change for this long is removed. */
    public static final Duration FINISHED_SESSION_TIMEOUT = Duration.ofMinutes(10);

    static final String LEFT_MESSAGE = "You left the session";
    static final String KICKED_MESSAGE = "You were removed from the session by the host";

    private final Map<SessionCode, GameSession> sessions =
            new ConcurrentHashMap<>();

    private final GameSessionTimer timer;
    private final GameSessionBroadcaster broadcaster;
    private final PlayerTokenRegistry playerTokens;
    private final PlayerConnections connections;
    private final SessionWatchers watchers;
    private final Clock clock;

    public GameSessionService(
            GameSessionTimer timer,
            GameSessionBroadcaster broadcaster,
            PlayerTokenRegistry playerTokens,
            PlayerConnections connections,
            SessionWatchers watchers,
            Clock clock
    ) {
        this.timer = timer;
        this.broadcaster = broadcaster;
        this.playerTokens = playerTokens;
        this.connections = connections;
        this.watchers = watchers;
        this.clock = clock;
    }

    /** A new session with a random id. */
    public GameSession createSession(GameBoard board) {
        return createSession(SessionCode.random(), board);
    }

    /**
     * A new session with the default timers; the host can change them in the
     * lobby. The client chooses the id, so that a load balancer in front of
     * several instances can route the create, like every later request about
     * the session, to the instance that will hold it (docs/api.md §3.2).
     *
     * <p>A closed session's id stays taken while any of its players is still
     * connected. Those connections stay open after the close, and player ids
     * start at 1 in every session, so in a new session under the same id
     * each one would act as the new player with its old id. Nothing can add
     * such a connection after the close, since the tokens are revoked. It
     * also stays taken while anyone is subscribed to its topic: a spectator
     * still showing the closed game would otherwise receive the new one's
     * broadcasts as if they were that session's.
     *
     * @throws GameSessionAlreadyExistsException if the id is taken
     */
    public GameSession createSession(SessionCode id, GameBoard board) {
        GameSession session = new GameSession(id, board, GameSessionTimeouts.defaults(), clock.instant());

        if (isHeldByClosedSession(id) || sessions.putIfAbsent(id, session) != null) {
            throw new GameSessionAlreadyExistsException(id);
        }

        // Nobody may ever join, so the idle lobby timeout starts now.
        synchronized (session) {
            scheduleCleanup(session);
        }

        return session;
    }

    /**
     * A cheap check before building a board for {@link #createSession}; the
     * create itself is what guarantees the id is unique.
     *
     * @throws GameSessionAlreadyExistsException if the id is taken
     */
    public void requireUnusedId(SessionCode id) {
        if (sessions.containsKey(id) || isHeldByClosedSession(id)) {
            throw new GameSessionAlreadyExistsException(id);
        }
    }

    // See createSession(SessionCode, GameBoard).
    private boolean isHeldByClosedSession(SessionCode id) {
        return connections.hasPlayersConnected(id) || watchers.hasWatchers(id);
    }

    /**
     * A summary of every session this instance holds, finished ones included,
     * in no particular order. Each is built under its session's lock. The
     * lobby list (SessionLobby) merges these with the other instances'.
     */
    public List<GameSessionSummaryResponse> localSummaries() {
        return sessions.values()
                .stream()
                .map(session -> {
                    synchronized (session) {
                        return GameSessionSummaryResponse.from(session);
                    }
                })
                .filter(summary -> summary.state() != GameSessionState.CLOSED)
                .toList();
    }

    public GameSession getSession(SessionCode sessionId) {
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
    public GameSessionResponse getSessionState(SessionCode sessionId) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            return GameSessionResponse.from(session);
        }
    }

    /**
     * Forgets a session at once, without the CLOSED broadcast, as if this
     * instance had lost it (as a restart does). Only tests use this, to set
     * up that situation. The app itself always ends a session through
     * {@link #closeSession}, so that clients see why it ended; don't call
     * this from application code.
     */
    public void removeSession(SessionCode sessionId) {
        timer.cancel(sessionId);
        timer.cancelCleanup(sessionId);

        GameSession session = sessions.get(sessionId);

        if (session != null) {
            // Tokens first, as in closeSession.
            session.getPlayers().forEach(player -> playerTokens.unregister(player.getToken()));
            sessions.remove(sessionId, session);
        }
    }

    /**
     * Adds a player and returns them, including their secret token. The
     * caller must hand the token only to the joining player.
     */
    public GameSessionPlayer joinSession(
            SessionCode sessionId,
            String playerName
    ) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            // Closed while this request waited for the lock.
            if (session.getState() == GameSessionState.CLOSED) {
                throw new GameSessionNotFoundException(sessionId);
            }

            GameSessionPlayer player = session.addPlayer(playerName, PlayerTokens.generate());
            playerTokens.register(
                    player.getToken(),
                    new PlayerPrincipal(sessionId, player.getId())
            );

            broadcast(session);

            return player;
        }
    }

    /**
     * The host changes the phase durations before the game starts. Any
     * duration the request leaves out keeps its current value. There is no
     * timer to re-arm, since the lobby has none.
     */
    public void updateTimers(
            SessionCode sessionId,
            Long playerId,
            SessionTimersRequest request
    ) {
        actKeepingTimer(sessionId, session ->
                session.updateTimeouts(playerId, request.applyTo(session.getTimeouts())));
    }

    /** The host's lobby settings. Like the timers, they have no timer to re-arm. */
    public void updateSettings(
            SessionCode sessionId,
            Long playerId,
            SessionSettingsRequest request
    ) {
        actKeepingTimer(sessionId, session -> session.updateLateJoin(playerId, request.allowLateJoin()));
    }

    public void leave(
            SessionCode sessionId,
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
            SessionCode sessionId,
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

    public void startGame(SessionCode sessionId, Long playerId) {
        act(sessionId, session -> session.startGame(playerId));
    }

    public void chooseClue(SessionCode sessionId, Long playerId, Long clueId) {
        act(sessionId, session -> session.chooseClue(playerId, clueId));
    }

    public void placeDailyDoubleWager(SessionCode sessionId, Long playerId, int wager) {
        act(sessionId, session -> session.placeDailyDoubleWager(playerId, wager));
    }

    // Final Jeopardy submissions don't change the state, so they deliberately
    // skip scheduleTimeout: re-arming the timer would restart the phase.
    public void placeFinalJeopardyWager(SessionCode sessionId, Long playerId, int wager) {
        actKeepingTimer(sessionId, session -> session.placeFinalJeopardyWager(playerId, wager));
    }

    public void answerFinalJeopardy(SessionCode sessionId, Long playerId, String answer) {
        actKeepingTimer(sessionId, session -> session.answerFinalJeopardy(playerId, answer));
    }

    public void buzz(SessionCode sessionId, Long playerId) {
        act(sessionId, session -> session.buzz(playerId));
    }

    public void answerClue(SessionCode sessionId, Long playerId, String answer) {
        act(sessionId, session -> session.answerClue(playerId, answer));
    }

    public void finishRevealingClue(SessionCode sessionId) {
        onTimeout(sessionId, GameSessionState.REVEALING_CLUE, GameSession::finishRevealingClue);
    }

    public void finishRevealingAnswer(SessionCode sessionId) {
        onTimeout(sessionId, GameSessionState.REVEALING_ANSWER, GameSession::finishRevealingAnswer);
    }

    /** The usual mutation: under the lock, act, re-arm the phase timer, broadcast. */
    private void act(SessionCode sessionId, Consumer<GameSession> action) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            action.accept(session);

            scheduleTimeout(session);
            broadcast(session);
        }
    }

    /** For actions that leave the state alone: re-arming the timer would restart the phase. */
    private void actKeepingTimer(SessionCode sessionId, Consumer<GameSession> action) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            action.accept(session);

            broadcast(session);
        }
    }

    /**
     * Ends a reveal as its timer would, without waiting for it (the reveals
     * are short, and tests skip them this way). It does nothing outside
     * {@code armedIn}. Real timers go through {@link #onPhaseTimeout}.
     */
    private void onTimeout(SessionCode sessionId, GameSessionState armedIn, Consumer<GameSession> action) {
        GameSession session = getSession(sessionId);

        synchronized (session) {
            if (session.getState() != armedIn) {
                return;
            }

            action.accept(session);

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
        SessionCode sessionId = session.getId();

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
        timer.scheduleCleanup(sessionId, timeout, () -> expireSession(session, version));
    }

    /**
     * A stale cleanup (the session changed since it was armed, or is gone)
     * is a no-op. It holds the session itself, not its id: a later session
     * can take the id, and its version could match.
     */
    private void expireSession(GameSession session, long version) {
        synchronized (session) {
            if (sessions.get(session.getId()) != session || session.getVersion() != version) {
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
     * GET /api/game-sessions/{id} returns 404. The id can't be reused while
     * they're open (see {@link #createSession(SessionCode, GameBoard)}).
     * Called with the lock held.
     */
    private void closeSession(GameSession session, SessionCloseReason reason) {
        SessionCode sessionId = session.getId();

        timer.cancel(sessionId);
        session.close(reason);
        broadcast(session);

        // Tokens before the id is freed: a player still connecting could
        // otherwise pass the interceptor's second token check after a new
        // session took the id, and act as that session's player. Removed only
        // if it's still this session, like everywhere else: one forgotten
        // without a close (removeSession) can be closed later, after a new
        // session took its id.
        session.getPlayers().forEach(player -> playerTokens.unregister(player.getToken()));
        sessions.remove(sessionId, session);
    }

    private void scheduleTimeout(GameSession session) {
        SessionCode sessionId = session.getId();
        GameSessionTimeouts timeouts = session.getTimeouts();

        timer.cancel(sessionId);

        GameSessionState state = session.getState();

        PhaseTimeout timeout = switch (state) {
            case CHOOSING_CLUE -> new PhaseTimeout(timeouts.clueChoice(), GameSession::timeoutChoosingClue);
            case REVEALING_CLUE -> new PhaseTimeout(timeouts.clueReveal(), GameSession::finishRevealingClue);
            case WAITING_FOR_BUZZER -> new PhaseTimeout(timeouts.buzzer(), GameSession::timeoutBuzzer);
            case WAITING_FOR_ANSWER -> new PhaseTimeout(timeouts.answer(), GameSession::timeoutAnswer);
            case REVEALING_ANSWER -> new PhaseTimeout(timeouts.answerReveal(), GameSession::finishRevealingAnswer);
            case WAITING_FOR_DAILY_DOUBLE_WAGER ->
                    new PhaseTimeout(timeouts.dailyDoubleWager(), GameSession::timeoutDailyDoubleWager);
            case FINAL_JEOPARDY_WAGERING ->
                    new PhaseTimeout(timeouts.finalJeopardyWager(), GameSession::timeoutFinalJeopardyWagering);
            case FINAL_JEOPARDY_ANSWERING ->
                    new PhaseTimeout(timeouts.finalJeopardyAnswer(), GameSession::timeoutFinalJeopardyAnswering);
            case FINAL_JEOPARDY_REVEALING ->
                    new PhaseTimeout(timeouts.finalJeopardyReveal(), GameSession::finishFinalJeopardyReveal);
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
        long timerId = session.getPhaseTimerId();
        timer.schedule(sessionId, timeout.duration(), () -> onPhaseTimeout(session, state, timerId, timeout.action()));
    }

    /**
     * A phase timer firing. Cancelling a timer can't stop one that is already
     * waiting for the lock, so it only acts if the session is still there,
     * in the state it was armed in, and no timer was armed or cleared since.
     * The state alone isn't enough: a buzz and a quick wrong answer return to
     * WAITING_FOR_BUZZER, and the old buzzer timeout would end the new
     * buzzing window at once. It holds the session itself, not its id: a
     * later session can take the id, and its state and timer id could match.
     */
    private void onPhaseTimeout(
            GameSession session,
            GameSessionState armedIn,
            long timerId,
            Consumer<GameSession> action
    ) {
        synchronized (session) {
            if (sessions.get(session.getId()) != session
                    || session.getState() != armedIn
                    || session.getPhaseTimerId() != timerId) {
                return;
            }

            action.accept(session);

            scheduleTimeout(session);
            broadcast(session);
        }
    }

    private record PhaseTimeout(Duration duration, Consumer<GameSession> action) {}
}
