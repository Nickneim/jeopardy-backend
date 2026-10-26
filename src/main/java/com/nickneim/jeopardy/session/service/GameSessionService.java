package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.controller.GameSessionBroadcaster;
import com.nickneim.jeopardy.session.controller.SessionWatchers;
import com.nickneim.jeopardy.session.dto.GameSessionResponse;
import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import com.nickneim.jeopardy.session.dto.SessionSettingsRequest;
import com.nickneim.jeopardy.session.dto.SessionTimersRequest;
import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.GameSessionAlreadyExistsException;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;
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
import java.util.Set;
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

    // Ids of closed sessions that may still be watched (isHeldByClosedSession).
    // An id is dropped once it's asked about and nobody watches it, and every
    // so often all of them are checked (see forgetUnwatchedIds), so they
    // don't pile up.
    private final Set<SessionCode> closedIds = ConcurrentHashMap.newKeySet();

    // closedIds is swept once it grows to this; see forgetUnwatchedIds.
    // Guarded by closedIds.
    private int closedIdsSweepAt = MIN_CLOSED_IDS_SWEEP;
    static final int MIN_CLOSED_IDS_SWEEP = 64;

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

    // For tests, through TestGameSessions: a new session with a random id.
    GameSession createSession(GameBoard board) {
        return createSession(SessionCode.random(), board);
    }

    /**
     * A new session with the default timers; the host can change them in the
     * lobby. The client chooses the id, so that a load balancer in front of
     * several instances can route the create, like every later request about
     * the session, to the instance that will hold it (docs/api.md §3.2).
     *
     * <p>A closed session's id stays taken while anyone is subscribed to its
     * topic: a spectator (or player) still showing the closed game would
     * otherwise receive the new one's broadcasts as if they were that
     * session's. Its players' connections, which stay open after the close,
     * can't act in a new session under the id: their PlayerPrincipal names
     * the closed session's instance.
     *
     * @throws GameSessionAlreadyExistsException if the id is taken
     */
    public GameSession createSession(SessionCode id, GameBoard board) {
        GameSession session = new GameSession(id, board, GameSessionTimeouts.defaults(), clock.instant());

        if (sessions.putIfAbsent(id, session) != null) {
            throw new GameSessionAlreadyExistsException(id);
        }
        // Only checked once the id is ours: a session that closes after the
        // check has already put its id in closedIds before freeing it, so
        // whichever way the two race, this sees it.
        if (isHeldByClosedSession(id)) {
            sessions.remove(id, session);
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

    /**
     * Whether a closed session still holds the id: anyone is subscribed to
     * its topic (see {@link #createSession(SessionCode, GameBoard)}). Only
     * closed ids are checked, so the broker is never asked about the random
     * ids of new sessions; an id is forgotten once nobody watches it.
     */
    private boolean isHeldByClosedSession(SessionCode id) {
        if (!closedIds.contains(id)) {
            return false;
        }
        if (watchers.hasWatchers(id)) {
            return true;
        }

        closedIds.remove(id);
        return false;
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
     * instance had lost it (as a restart does). Only tests use this (through
     * TestGameSessions), to set up that situation. The app itself always
     * ends a session through {@link #closeSession}, so that clients see why
     * it ended.
     */
    void removeSession(SessionCode sessionId) {
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
                    PlayerPrincipal.of(session, player.getId())
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
    public void updateTimers(PlayerPrincipal host, SessionTimersRequest request) {
        actKeepingTimer(host, session ->
                session.updateTimeouts(host.playerId(), request.applyTo(session.getTimeouts())));
    }

    /** The host's lobby settings. Like the timers, they have no timer to re-arm. */
    public void updateSettings(PlayerPrincipal host, SessionSettingsRequest request) {
        actKeepingTimer(host, session -> session.updateLateJoin(host.playerId(), request.allowLateJoin()));
    }

    public void leave(PlayerPrincipal player) {
        GameSession session = sessionOf(player);

        synchronized (session) {
            GameSessionState before = session.getState();
            GameSessionPlayer departed = session.leave(player.playerId());

            afterDeparture(session, departed, before, LEFT_MESSAGE);
        }
    }

    public void kick(PlayerPrincipal host, Long playerId) {
        GameSession session = sessionOf(host);

        synchronized (session) {
            GameSessionState before = session.getState();
            GameSessionPlayer kicked = session.kick(host.playerId(), playerId);

            afterDeparture(session, kicked, before, KICKED_MESSAGE);
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

        connections.close(PlayerPrincipal.of(session, player.getId()), message);
    }

    public void startGame(PlayerPrincipal player) {
        act(player, session -> session.startGame(player.playerId()));
    }

    public void chooseClue(PlayerPrincipal player, Long clueId) {
        act(player, session -> session.chooseClue(player.playerId(), clueId));
    }

    public void placeDailyDoubleWager(PlayerPrincipal player, int wager) {
        act(player, session -> session.placeDailyDoubleWager(player.playerId(), wager));
    }

    // Final Jeopardy submissions don't change the state, so they deliberately
    // skip scheduleTimeout: re-arming the timer would restart the phase.
    public void placeFinalJeopardyWager(PlayerPrincipal player, int wager) {
        actKeepingTimer(player, session -> session.placeFinalJeopardyWager(player.playerId(), wager));
    }

    public void answerFinalJeopardy(PlayerPrincipal player, String answer) {
        actKeepingTimer(player, session -> session.answerFinalJeopardy(player.playerId(), answer));
    }

    public void buzz(PlayerPrincipal player) {
        act(player, session -> session.buzz(player.playerId()));
    }

    public void answerClue(PlayerPrincipal player, String answer) {
        act(player, session -> session.answerClue(player.playerId(), answer));
    }

    // For tests, through TestGameSessions: real reveals end on their timers.
    void finishRevealingClue(SessionCode sessionId) {
        onTimeout(sessionId, GameSessionState.REVEALING_CLUE, GameSession::finishRevealingClue);
    }

    void finishRevealingAnswer(SessionCode sessionId) {
        onTimeout(sessionId, GameSessionState.REVEALING_ANSWER, GameSession::finishRevealingAnswer);
    }

    /**
     * The session the player is acting in. It must be the very session they
     * joined: a closed session's players are still connected, and a new
     * session under its id has players with the same ids.
     */
    private GameSession sessionOf(PlayerPrincipal player) {
        GameSession session = getSession(player.sessionId());

        if (!player.isIn(session)) {
            throw new InvalidGameActionException(GameErrorCode.PLAYER_NOT_IN_SESSION, "You're not in this session!");
        }

        return session;
    }

    /** The usual mutation: under the lock, act, re-arm the phase timer, broadcast. */
    private void act(PlayerPrincipal player, Consumer<GameSession> action) {
        GameSession session = sessionOf(player);

        synchronized (session) {
            action.accept(session);

            scheduleTimeout(session);
            broadcast(session);
        }
    }

    /** For actions that leave the state alone: re-arming the timer would restart the phase. */
    private void actKeepingTimer(PlayerPrincipal player, Consumer<GameSession> action) {
        GameSession session = sessionOf(player);

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
     * anyone watches it (see {@link #createSession(SessionCode, GameBoard)}).
     * Called with the lock held.
     */
    private void closeSession(GameSession session, SessionCloseReason reason) {
        SessionCode sessionId = session.getId();

        timer.cancel(sessionId);
        session.close(reason);
        broadcast(session);

        // Tokens before the id is freed, so nobody connects as one of its
        // players once a new session may have the id (they couldn't act in
        // it anyway: their principal names this session's instance). The id
        // goes in closedIds before it's freed, so a create that takes it
        // finds it there. Removed only if it's still this session, like
        // everywhere else: one forgotten without a close (removeSession) can
        // be closed later, after a new session took its id.
        session.getPlayers().forEach(player -> playerTokens.unregister(player.getToken()));
        closedIds.add(sessionId);
        sessions.remove(sessionId, session);
        forgetUnwatchedIds();
    }

    /**
     * Drops the closed ids nobody watches any more, once there are twice as
     * many as after the last sweep. A sweep asks the broker about every
     * closed id, so this way a close costs a constant number of lookups on
     * average, while the ids still can't pile up.
     */
    private void forgetUnwatchedIds() {
        synchronized (closedIds) {
            if (closedIds.size() < closedIdsSweepAt) {
                return;
            }

            closedIds.removeIf(closedId -> !watchers.hasWatchers(closedId));
            closedIdsSweepAt = Math.max(MIN_CLOSED_IDS_SWEEP, 2 * closedIds.size());
        }
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
