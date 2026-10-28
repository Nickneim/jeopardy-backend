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
import com.nickneim.jeopardy.session.model.HostMode;
import com.nickneim.jeopardy.session.model.TimerMode;
import com.nickneim.jeopardy.session.controller.TrebekSubscriptions;
import com.nickneim.jeopardy.session.model.SessionCloseReason;
import com.nickneim.jeopardy.session.security.PlayerConnections;
import com.nickneim.jeopardy.session.security.PlayerConnectionsChanged;
import com.nickneim.jeopardy.session.security.PlayerPrincipal;
import com.nickneim.jeopardy.session.security.PlayerTokenRegistry;
import com.nickneim.jeopardy.session.security.PlayerTokens;
import com.nickneim.jeopardy.session.model.SessionCode;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

@Service
public class GameSessionService {

    /** A lobby (WAITING_FOR_START) with no change for this long is removed. */
    public static final Duration IDLE_LOBBY_TIMEOUT = Duration.ofMinutes(30);

    /** A finished game with no change for this long is removed. */
    public static final Duration FINISHED_SESSION_TIMEOUT = Duration.ofMinutes(10);

    /** A session whose players have all been away (no open connection) for this long is closed. */
    public static final Duration EVERYONE_AWAY_TIMEOUT = Duration.ofMinutes(2);

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
        timer.cancelAbandonment(sessionId);

        GameSession session = sessions.get(sessionId);

        if (session != null) {
            // Tokens first, as in closeSession.
            session.getMembers().forEach(player -> playerTokens.unregister(player.getToken()));
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
     * duration the request leaves out keeps its current value.
     */
    public void updateTimers(PlayerPrincipal host, SessionTimersRequest request) {
        act(host, session ->
                session.updateTimeouts(host.playerId(), request.applyTo(session.getTimeouts())));
    }

    /**
     * The host's lobby settings. Any setting the request leaves out keeps its
     * value; each is checked the same way, so either all apply or none.
     */
    public void updateSettings(PlayerPrincipal host, SessionSettingsRequest request) {
        if (request.isEmpty()) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_REQUEST, "At least one setting must be set");
        }

        act(host, session -> {
            if (request.allowLateJoin() != null) {
                session.updateLateJoin(host.playerId(), request.allowLateJoin());
            }
            if (request.hostMode() != null) {
                session.updateHostMode(host.playerId(), request.hostMode());
            }
            if (request.timerModes() != null) {
                session.updateTimerModes(host.playerId(), request.timerModes().applyTo(session.getTimerModes()));
            }
        });
    }

    public void leave(PlayerPrincipal player) {
        depart(player, session -> session.leave(player.playerId()), LEFT_MESSAGE);
    }

    public void kick(PlayerPrincipal host, Long playerId) {
        depart(host, session -> session.kick(host.playerId(), playerId), KICKED_MESSAGE);
    }

    /**
     * The departing player's token stops working at once and their open
     * connections are closed, so a client that reconnects is rejected like
     * any unknown token. If nobody is left to play, the session is closed
     * (see {@link GameSession#isAbandoned}), and so is a game Trebek left,
     * which can't go on without them.
     */
    private void depart(
            PlayerPrincipal actor,
            Function<GameSession, GameSessionPlayer> departure,
            String message
    ) {
        GameSession session = sessionOf(actor);

        synchronized (session) {
            long phaseBefore = session.getPhaseNumber();
            GameSessionPlayer departed = departure.apply(session);

            playerTokens.unregister(departed.getToken());

            if (session.getState() == GameSessionState.CLOSED) {
                // Trebek left during the game.
                closeSession(session, session.getCloseReason());
            } else if (session.isAbandoned()) {
                closeSession(session, SessionCloseReason.ABANDONED);
            } else {
                // Usually the phase goes on: the last player who could still
                // buzz leaving ends it, and a departed chooser's control
                // passing on gives the new chooser the full time to pick.
                afterChange(session, phaseBefore);
            }

            connections.close(PlayerPrincipal.of(session, departed.getId()), message);
        }
    }

    /**
     * A connection of a player opened or closed: they're away while they
     * have none. Being away never changes the state (see
     * {@link GameSession#setAway}), so no timer is re-armed.
     */
    @EventListener
    public void onPlayerConnectionsChanged(PlayerConnectionsChanged event) {
        PlayerPrincipal player = event.player();
        GameSession session = sessions.get(player.sessionId());

        if (session == null || !player.isIn(session)) {
            return;
        }

        synchronized (session) {
            if (session.getState() == GameSessionState.CLOSED) {
                return;
            }

            // Asked now, under the lock, rather than taken from the event:
            // two connections' events can arrive in either order.
            if (session.setAway(player.playerId(), !connections.isConnected(player))) {
                broadcast(session);
            }
        }
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

    public void placeFinalJeopardyWager(PlayerPrincipal player, int wager) {
        act(player, session -> session.placeFinalJeopardyWager(player.playerId(), wager));
    }

    public void answerFinalJeopardy(PlayerPrincipal player, String answer) {
        act(player, session -> session.answerFinalJeopardy(player.playerId(), answer));
    }

    public void buzz(PlayerPrincipal player) {
        act(player, session -> session.buzz(player.playerId()));
    }

    public void answerClue(PlayerPrincipal player, String answer) {
        act(player, session -> session.answerClue(player.playerId(), answer));
    }

    // -------------------------------------------------------------------------
    // Trebek
    // -------------------------------------------------------------------------

    public void advance(PlayerPrincipal trebek) {
        act(trebek, session -> session.advance(trebek.playerId()));
    }

    public void returnClue(PlayerPrincipal trebek) {
        act(trebek, session -> session.returnClue(trebek.playerId()));
    }

    public void rule(PlayerPrincipal trebek, boolean correct) {
        act(trebek, session -> session.rule(trebek.playerId(), correct));
    }

    public void ruleFinal(PlayerPrincipal trebek, Long playerId, boolean correct) {
        act(trebek, session -> session.ruleFinal(trebek.playerId(), playerId, correct));
    }

    public void adjustScore(PlayerPrincipal trebek, Long playerId, int amount) {
        act(trebek, session -> session.adjustScore(trebek.playerId(), playerId, amount));
    }

    /**
     * Stops the clock: the phase timer is cancelled, and the phase keeps the
     * time it has left. Pausing again changes nothing and isn't broadcast.
     */
    public void pause(PlayerPrincipal trebek) {
        GameSession session = sessionOf(trebek);

        synchronized (session) {
            if (session.pause(trebek.playerId(), System.nanoTime())) {
                timer.cancel(session.getId());
                broadcast(session);
            }
        }
    }

    /**
     * Restarts the clock: the phase timer is armed again with the time the
     * phase had left. Resuming when not paused changes nothing and isn't
     * broadcast.
     */
    public void resume(PlayerPrincipal trebek) {
        GameSession session = sessionOf(trebek);

        synchronized (session) {
            long now = System.nanoTime();

            if (!session.resume(trebek.playerId(), now)) {
                return;
            }

            PhaseTimeout timeout = phaseTimeout(session);
            Duration remaining = session.getPhaseRemaining(now);
            if (timeout != null && remaining != null) {
                armPhaseTimer(session, remaining, timeout.action());
            }
            broadcast(session);
        }
    }

    /**
     * Someone subscribed to Trebek's private view: if it's this session's
     * Trebek, they get it now rather than at the next change.
     */
    @EventListener
    public void onTrebekViewRequested(TrebekSubscriptions.TrebekViewRequested event) {
        PlayerPrincipal player = event.player();
        GameSession session = sessions.get(player.sessionId());

        if (session == null || !player.isIn(session)) {
            return;
        }

        synchronized (session) {
            GameSessionPlayer trebek = session.getTrebek();

            if (trebek != null && trebek.getId().equals(player.playerId())) {
                broadcaster.sendTrebekView(session);
            }
        }
    }

    // For tests, through TestGameSessions: real reveals and title cards end
    // on their timers. Each does nothing outside its phase.
    void finishRoundIntro(SessionCode sessionId) {
        mutate(
                getSession(sessionId),
                session -> session.getState() == GameSessionState.ROUND_INTRO,
                GameSession::finishRoundIntro
        );
    }

    void finishRevealingDailyDouble(SessionCode sessionId) {
        mutate(
                getSession(sessionId),
                session -> session.getState() == GameSessionState.REVEALING_DAILY_DOUBLE,
                GameSession::finishRevealingDailyDouble
        );
    }

    void finishRevealingClue(SessionCode sessionId) {
        mutate(
                getSession(sessionId),
                session -> session.getState() == GameSessionState.REVEALING_CLUE,
                GameSession::finishRevealingClue
        );
    }

    void finishRevealingAnswer(SessionCode sessionId) {
        mutate(
                getSession(sessionId),
                session -> session.getState() == GameSessionState.REVEALING_ANSWER,
                GameSession::finishRevealingAnswer
        );
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

    /** A player's action, applied through {@link #mutate}. */
    private void act(PlayerPrincipal player, Consumer<GameSession> action) {
        mutate(sessionOf(player), session -> true, action);
    }

    /**
     * Every mutation goes through here: under the session's lock, and only
     * if {@code stillValid} holds, apply the action, re-arm the phase timer
     * if it started a new phase, and broadcast.
     */
    private void mutate(
            GameSession session,
            Predicate<GameSession> stillValid,
            Consumer<GameSession> action
    ) {
        synchronized (session) {
            if (!stillValid.test(session)) {
                return;
            }

            long phaseBefore = session.getPhaseNumber();
            action.accept(session);

            afterChange(session, phaseBefore);
        }
    }

    /**
     * Re-arms the phase timer if a new phase began since {@code phaseBefore}
     * (see {@link GameSession#getPhaseNumber}), then broadcasts. Anything
     * else (a Final Jeopardy wager or answer, a lobby setting, most
     * departures) leaves the phase going: re-arming the timer would restart it.
     */
    private void afterChange(GameSession session, long phaseBefore) {
        if (session.getPhaseNumber() != phaseBefore) {
            scheduleTimeout(session);
        }

        broadcast(session);
    }

    // Every state change is broadcast through here, so the version can't be
    // forgotten. Always called while holding the session's lock.
    private void broadcast(GameSession session) {
        session.incrementVersion();
        broadcaster.broadcast(session);
        scheduleCleanup(session);
    }

    /**
     * Arms the session's cleanup timers after every change: a lobby or a
     * finished game is removed once it has gone without changes for its
     * timeout (a game in progress always moves on by itself, so it has
     * none), and a game in progress once every player has been away for
     * {@link #EVERYONE_AWAY_TIMEOUT}. Called with the session's lock held.
     */
    private void scheduleCleanup(GameSession session) {
        SessionCode sessionId = session.getId();

        if (isInProgress(session) && session.isEveryoneAway()) {
            timer.scheduleAbandonment(sessionId, EVERYONE_AWAY_TIMEOUT, () -> abandonSession(session));
        } else {
            timer.cancelAbandonment(sessionId);
        }

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

    // Whether the game is being played: the lobby and a finished game have
    // their own, longer cleanup timeouts, and a host waiting in the lobby on
    // a phone that went to sleep (which drops its connection) mustn't lose it.
    private static boolean isInProgress(GameSession session) {
        return switch (session.getState()) {
            case WAITING_FOR_START, FINISHED_GAME, CLOSED -> false;
            default -> true;
        };
    }

    // A stale abandonment (someone came back, the game ended, or the
    // session is gone) is a no-op. Like expireSession, it holds the session
    // itself, not its id.
    private void abandonSession(GameSession session) {
        synchronized (session) {
            if (sessions.get(session.getId()) == session && isInProgress(session) && session.isEveryoneAway()) {
                closeSession(session, SessionCloseReason.ABANDONED);
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
        session.getMembers().forEach(player -> playerTokens.unregister(player.getToken()));
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

    /**
     * Arms the phase timer of the phase that just began, or clears it if the
     * phase has none. While Trebek has paused the clock the phase is
     * recorded with its full time, and nothing is armed until it's resumed.
     */
    private void scheduleTimeout(GameSession session) {
        timer.cancel(session.getId());

        PhaseTimeout timeout = phaseTimeout(session);

        if (timeout == null) {
            session.clearPhaseTimer();
            return;
        }

        // Recorded on the session so every response can say how long the
        // phase has left.
        session.startPhaseTimer(timeout.duration(), System.nanoTime());

        if (!session.isPaused()) {
            armPhaseTimer(session, timeout.duration(), timeout.action());
        }
    }

    private void armPhaseTimer(GameSession session, Duration delay, Consumer<GameSession> action) {
        GameSessionState state = session.getState();
        long timerId = session.getPhaseTimerId();

        timer.schedule(session.getId(), delay, () -> onPhaseTimeout(session, state, timerId, action));
    }

    /**
     * The timer of the session's current phase, or null if it has none: in
     * a Trebek session, also a phase whose timer mode is TREBEK (it waits
     * for Trebek's advance), and the Final Jeopardy reveal while it waits for
     * a ruling. Each Final Jeopardy reveal step is a phase of its own, with
     * an even share of the reveal's duration, which is per player.
     */
    private PhaseTimeout phaseTimeout(GameSession session) {
        GameSessionTimeouts timeouts = session.getTimeouts();
        GameSessionState state = session.getState();
        boolean trebekSession = session.getHostMode() == HostMode.TREBEK;

        if (trebekSession && session.getTimerModes().of(state) == TimerMode.TREBEK) {
            return null;
        }

        return switch (state) {
            case ROUND_INTRO -> new PhaseTimeout(timeouts.roundIntro(), GameSession::finishRoundIntro);
            case REVEALING_DAILY_DOUBLE ->
                    new PhaseTimeout(timeouts.dailyDoubleReveal(), GameSession::finishRevealingDailyDouble);
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
            case FINAL_JEOPARDY_REVEALING -> {
                if (session.isRevealWaitingForRuling()) {
                    yield null;
                }
                Duration step = timeouts.finalJeopardyReveal().dividedBy(GameSession.REVEAL_STEPS_PER_PLAYER);
                yield new PhaseTimeout(step, GameSession::timeoutFinalJeopardyReveal);
            }
            // No timeout for these states: they wait for a player or Trebek.
            case WAITING_FOR_START, CLUE_PICKED, WAITING_FOR_RULING, FINISHED_GAME, CLOSED -> null;
        };
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
        mutate(
                session,
                current -> sessions.get(current.getId()) == current
                        && current.getState() == armedIn
                        && current.getPhaseTimerId() == timerId,
                action
        );
    }

    private record PhaseTimeout(Duration duration, Consumer<GameSession> action) {}
}
