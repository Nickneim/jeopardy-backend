package com.nickneim.jeopardy.session.model;

import com.nickneim.jeopardy.game.entity.ClueType;
import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.answer.AnswerCheck;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.board.InvalidBoardException;
import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;
import com.nickneim.jeopardy.session.exception.SessionNotJoinableException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class GameSession {

    // The lowest Daily Double wager, unless the round's values are lower still.
    private static final int MINIMUM_DAILY_DOUBLE_WAGER = 5;

    /** The most Trebek can add to or take from a score at once. */
    public static final int MAX_SCORE_ADJUSTMENT = 100_000;

    /**
     * Final Jeopardy reveal steps per player: wager, answer, award (see
     * FinalRevealStep). The reveal's clock gives each step an even share of
     * a player's reveal time.
     */
    public static final int REVEAL_STEPS_PER_PLAYER = 3;

    private static final AtomicLong INSTANCE_IDS = new AtomicLong();

    private final SessionCode id;
    // Tells this session apart from any other that has (or had) its id: a
    // closed session's id can be reused, and player ids restart at 1.
    private final long instanceId = INSTANCE_IDS.incrementAndGet();
    // The board as it was loaded, and the one played: it without the clue
    // types the host disabled in the lobby.
    private final GameBoard fullBoard;
    private GameBoard board;
    private Set<ClueType> disabledClueTypes = EnumSet.noneOf(ClueType.class);
    private final Instant createdAt;
    // Every player still in the session, in join order. A player who left
    // while holding a role stays here, marked as leaving, until the role ends.
    // Trebek is never here.
    private final List<GameSessionPlayer> players = new ArrayList<>();
    private GameSessionTimeouts timeouts;

    // Ids are never reused, so a removed player's id can't come back as
    // someone else's.
    private long nextPlayerId = 1;
    private Long hostPlayerId;
    private boolean allowLateJoin = true;
    private int joinScore = 0;

    // In TREBEK mode the host runs the show instead of playing: they're
    // kept here, out of players, so nothing about players (scores, control,
    // buzzing, the join score) ever counts them. Null in CONTESTANT mode.
    private HostMode hostMode = HostMode.CONTESTANT;
    private GameSessionPlayer trebek;
    private GameSessionTimerModes timerModes = GameSessionTimerModes.defaults();

    private GameRound round;
    private GameSessionState state;
    private SessionCloseReason closeReason;
    private long version;

    // The timer of the current phase, set by GameSessionService whenever it
    // arms one. The deadline is in System.nanoTime() terms, which never jumps.
    private Duration phaseDuration;
    private long phaseDeadlineNanos;
    // Changes whenever a phase timer is armed or cleared, so a timeout can
    // tell whether it is still the current one: the state alone can't, since
    // a session can leave a state and come back before a stale timeout runs.
    private long phaseTimerId;
    // Goes up whenever a new phase begins: the state changes, or control
    // passes to someone else while choosing. The service re-arms the phase
    // timer exactly when it moved.
    private long phaseNumber;
    // Trebek stopped the clock. A phase timer then keeps what it has left
    // here instead of a deadline, and nothing is armed until it's resumed.
    private boolean paused;
    private Duration pausedRemaining;

    private Long playerChoosingClue;
    private Long chosenClueId;
    // Who chose the clue in play, so a choice that arrives just after the
    // timer or Trebek chose for the chooser can be told it was too late.
    private ChosenBy chosenBy;
    // The wager on the clue in play, once placed if it's a Daily Double;
    // null otherwise. Reset whenever a clue is chosen.
    private Integer dailyDoubleWager;

    private final Set<Long> playersAlreadyBuzzed = new HashSet<>();
    // Every clue chosen so far, across all rounds. Which clues are still
    // available is derived from this and the board, never stored.
    private final Set<Long> playedClues = new HashSet<>();
    private Long playerWhoBuzzed;
    // Answers to the clue in play, in order. Cleared when the next clue is
    // chosen and when Final Jeopardy starts, so it never holds stale entries.
    private final List<ClueAnswer> clueAnswers = new ArrayList<>();
    // In WAITING_FOR_RULING, what the answering player submitted (a
    // timed-out answer if nothing), until Trebek rules on it. Secret.
    private ClueAnswer pendingAnswer;

    private FinalJeopardy finalJeopardy = new FinalJeopardy();
    // The Final Jeopardy reveal: the players in the order they're revealed,
    // set when it starts, and how many steps were taken.
    private List<Long> revealOrder;
    private int revealSteps;
    // The reveal's clock reached a player's award before Trebek ruled on
    // them, so it waits for the ruling.
    private boolean revealWaitingForRuling;

    private enum ChosenBy { CHOOSER, TIMER, TREBEK }

    public GameSession(SessionCode id, GameBoard board) {
        this(id, board, GameSessionTimeouts.defaults(), Instant.now());
    }

    public GameSession(SessionCode id, GameBoard board, GameSessionTimeouts timeouts, Instant createdAt) {
        this.id = id;
        this.fullBoard = board;
        this.board = board;
        this.timeouts = timeouts;
        this.createdAt = createdAt;
        this.round = board.getFirstRound();
        this.state = GameSessionState.WAITING_FOR_START;
    }

    public SessionCode getId() {
        return id;
    }

    /** Unique among every session this server has created, unlike the id. */
    public long getInstanceId() {
        return instanceId;
    }

    /** The board being played: without the clue types the host disabled. */
    public GameBoard getBoard() {
        return board;
    }

    /** The board as it was loaded, every clue type included. */
    public GameBoard getFullBoard() {
        return fullBoard;
    }

    /** The clue types the host left out of the game; never TEXT. */
    public Set<ClueType> getDisabledClueTypes() {
        return Collections.unmodifiableSet(disabledClueTypes);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Every player in the session, including those waiting to be removed. Never Trebek. */
    public List<GameSessionPlayer> getPlayers() {
        return List.copyOf(players);
    }

    /** Every player and Trebek: everyone who holds a token in this session. */
    public List<GameSessionPlayer> getMembers() {
        List<GameSessionPlayer> members = new ArrayList<>(players);
        if (trebek != null) {
            members.add(trebek);
        }
        return members;
    }

    /** Players who haven't left. Never Trebek. */
    public List<GameSessionPlayer> getActivePlayers() {
        return players.stream()
                .filter(player -> !player.isLeaving())
                .toList();
    }

    /** The host, or null while nobody has joined. In TREBEK mode, Trebek. */
    public Long getHostPlayerId() {
        return hostPlayerId;
    }

    public HostMode getHostMode() {
        return hostMode;
    }

    /** The host running the show in TREBEK mode; null in CONTESTANT mode. */
    public GameSessionPlayer getTrebek() {
        return trebek;
    }

    public GameSessionTimerModes getTimerModes() {
        return timerModes;
    }

    public GameSessionTimeouts getTimeouts() { return timeouts; }

    public GameRound getRound() {
        return round;
    }

    public GameSessionState getState() {
        return state;
    }

    /** Why the session was closed, or null while it's open. */
    public SessionCloseReason getCloseReason() {
        return closeReason;
    }

    public Long getPlayerChoosingClue() {
        return playerChoosingClue;
    }

    public Long getChosenClueId() {
        return chosenClueId;
    }

    /** Clues of the current round that haven't been played yet. */
    public Set<Long> getAvailableClues() {
        Set<Long> available = new HashSet<>(board.getClueIds(round));
        available.removeAll(playedClues);
        return Collections.unmodifiableSet(available);
    }

    public Set<Long> getPlayedClues() {
        return Set.copyOf(playedClues);
    }

    /**
     * The player who must answer right now, or whose answer Trebek is ruling
     * on; null if nobody is answering.
     */
    public Long getPlayerAnswering() {
        return state == GameSessionState.WAITING_FOR_ANSWER || state == GameSessionState.WAITING_FOR_RULING
                ? playerWhoBuzzed
                : null;
    }

    /**
     * Players who already buzzed on the clue in play and can't buzz again.
     * Empty outside the buzzing and answering phases, because the underlying
     * set is only reset when the next clue's reveal finishes.
     */
    public Set<Long> getPlayersLockedOut() {
        return switch (state) {
            case WAITING_FOR_BUZZER, WAITING_FOR_ANSWER, WAITING_FOR_RULING -> Set.copyOf(playersAlreadyBuzzed);
            default -> Set.of();
        };
    }

    /**
     * Every answer given to the current clue (or, until the next clue is
     * chosen, the last one played), oldest first. Empty during Final
     * Jeopardy, which has its own reveal. In a Trebek session an answer is
     * only added once Trebek rules on it.
     */
    public List<ClueAnswer> getClueAnswers() {
        return List.copyOf(clueAnswers);
    }

    /**
     * What the answering player submitted, while Trebek hasn't ruled on it
     * (WAITING_FOR_RULING); otherwise null. Only for Trebek's eyes.
     */
    public ClueAnswer getPendingAnswer() {
        return state == GameSessionState.WAITING_FOR_RULING ? pendingAnswer : null;
    }

    /** The wager on the Daily Double in play once it is placed, otherwise null. */
    public Integer getDailyDoubleWager() {
        return switch (state) {
            case REVEALING_CLUE, WAITING_FOR_ANSWER, WAITING_FOR_RULING, REVEALING_ANSWER -> dailyDoubleWager;
            default -> null;
        };
    }

    /**
     * Adds a player exactly as given (id, score, away), skipping the join
     * checks. For tests, which set players up in a known state; the app
     * joins players through {@link #addPlayer(String, String)}. The first
     * player added becomes the host.
     */
    public void addPlayer(GameSessionPlayer player) {
        players.add(player);
        nextPlayerId = Math.max(nextPlayerId, player.getId() + 1);

        if (hostPlayerId == null) {
            hostPlayerId = player.getId();
        }
    }

    /**
     * Adds a new player numbered after every id this session has handed out
     * (1, 2, 3, ...), including players who have since left. Small ids are
     * safe as JavaScript numbers; they are only unique within the session,
     * which is fine because a player is always identified by their session
     * and token, never their id alone.
     */
    public GameSessionPlayer addPlayer(String name, String token) {
        if (!isJoinable()) {
            throw new SessionNotJoinableException(switch (state) {
                case FINAL_JEOPARDY_ANSWERING, FINAL_JEOPARDY_REVEALING ->
                        "Final Jeopardy is underway and doesn't accept new players";
                case FINISHED_GAME -> "This game has finished and doesn't accept new players";
                default -> "This game has already started and doesn't accept new players";
            });
        }

        GameSessionPlayer player = new GameSessionPlayer(nextPlayerId, name, joinScore, token);
        // Nothing is waited for until they connect (see setAway).
        player.setAway(true);
        addPlayer(player);

        return player;
    }

    /**
     * Whether someone can join right now: always in the lobby, and during the
     * game unless the host blocked it. Never once Final Jeopardy's wagering
     * is over (a new player couldn't wager) or the game has finished (there
     * is nothing left to play). Anyone can still watch without joining.
     */
    public boolean isJoinable() {
        return switch (state) {
            case WAITING_FOR_START -> true;
            case FINAL_JEOPARDY_ANSWERING, FINAL_JEOPARDY_REVEALING, FINISHED_GAME, CLOSED -> false;
            default -> allowLateJoin;
        };
    }

    /** Whether players can join after the game has started. */
    public boolean isLateJoinAllowed() {
        return allowLateJoin;
    }

    /**
     * The score a player starts with when they join. It never goes above 0,
     * and only rises when the lowest scorers are pushed up, so leaving and
     * joining again never gives anyone a better score than staying.
     * See {@link #changeScores}.
     */
    public int getJoinScore() {
        return joinScore;
    }

    /**
     * Goes up by one with every broadcast, so a client that both fetches the
     * state and receives broadcasts can keep whichever is newest.
     */
    public long getVersion() {
        return version;
    }

    public void incrementVersion() {
        version++;
    }

    /**
     * Records that the current phase ends {@code duration} after
     * {@code nowNanos}; while paused, it starts with its full time and
     * doesn't count down until resumed.
     */
    public void startPhaseTimer(Duration duration, long nowNanos) {
        phaseDuration = duration;
        if (paused) {
            pausedRemaining = duration;
        } else {
            phaseDeadlineNanos = nowNanos + duration.toNanos();
        }
        phaseTimerId++;
    }

    /** Records that the current phase has no timer. */
    public void clearPhaseTimer() {
        phaseDuration = null;
        pausedRemaining = null;
        phaseTimerId++;
    }

    /** Identifies the phase timer armed last; see {@link #startPhaseTimer}. */
    public long getPhaseTimerId() {
        return phaseTimerId;
    }

    /** Changes whenever a new phase begins; see {@link #phaseNumber}. */
    public long getPhaseNumber() {
        return phaseNumber;
    }

    /** The full length of the current phase, or null if it has no timer. */
    public Duration getPhaseDuration() {
        return phaseDuration;
    }

    /**
     * How long the current phase has left at {@code nowNanos} (never
     * negative), or null if it has no timer. While paused, what it had left
     * when the clock stopped.
     */
    public Duration getPhaseRemaining(long nowNanos) {
        if (phaseDuration == null) {
            return null;
        }
        if (paused) {
            return pausedRemaining;
        }

        return Duration.ofNanos(Math.max(0, phaseDeadlineNanos - nowNanos));
    }

    /** Whether Trebek stopped the clock. Always false in CONTESTANT mode. */
    public boolean isPaused() {
        return paused;
    }

    // -------------------------------------------------------------------------
    // Lobby: host, timers, leaving
    // -------------------------------------------------------------------------

    /** Only the host can change the timers, and only before the game starts. */
    public void updateTimeouts(Long playerId, GameSessionTimeouts newTimeouts) {
        requireActiveMember(playerId);
        requireHost(playerId, "Only the host can change the timers!");

        requireState(GameSessionState.WAITING_FOR_START, "Timers can only be changed before the game starts!");

        timeouts = newTimeouts;
    }

    /**
     * Only the host can block or allow joining after the start, and only
     * before the game starts. Joining the lobby is always allowed.
     */
    public void updateLateJoin(Long playerId, boolean allowed) {
        requireSettingsChange(playerId);

        allowLateJoin = allowed;
    }

    /**
     * The host chooses whether they play or run the show as Trebek, before
     * the game starts. As Trebek they leave players (keeping their id and
     * token); back as a contestant they return to their place in join order.
     */
    public void updateHostMode(Long playerId, HostMode mode) {
        requireSettingsChange(playerId);

        if (mode == hostMode) {
            return;
        }

        GameSessionPlayer host = getMember(hostPlayerId);

        if (mode == HostMode.TREBEK) {
            players.remove(host);
            trebek = host;
        } else {
            trebek = null;
            // Scores can't change in the lobby, so theirs is still the join score.
            int position = (int) players.stream().filter(player -> player.getId() < host.getId()).count();
            players.add(position, host);
        }

        hostMode = mode;
    }

    /** Which phases wait for Trebek; it only matters in TREBEK mode. */
    public void updateTimerModes(Long playerId, GameSessionTimerModes modes) {
        requireSettingsChange(playerId);

        timerModes = modes;
    }

    /**
     * The host leaves clue types out of the game (media clues often don't
     * load), before it starts. {@code types} replaces the disabled set; text
     * clues can't be disabled, and neither can every clue the board has.
     */
    public void updateDisabledClueTypes(Long playerId, Set<ClueType> types) {
        requireSettingsChange(playerId);

        if (types.contains(ClueType.TEXT)) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_REQUEST, "Text clues can't be disabled!");
        }

        GameBoard filtered;
        try {
            filtered = types.isEmpty() ? fullBoard : fullBoard.withoutClueTypes(types);
        } catch (InvalidBoardException e) {
            throw new InvalidGameActionException(
                    GameErrorCode.INVALID_REQUEST, "That would leave no clues to play!"
            );
        }

        board = filtered;
        round = board.getFirstRound();
        disabledClueTypes = types.isEmpty() ? EnumSet.noneOf(ClueType.class) : EnumSet.copyOf(types);
    }

    private void requireSettingsChange(Long playerId) {
        requireActiveMember(playerId);
        requireHost(playerId, "Only the host can change the settings!");

        requireState(GameSessionState.WAITING_FOR_START, "Settings can only be changed before the game starts!");
    }

    /**
     * The player (or Trebek) leaves the session. They can't act any more,
     * but see {@link #removeDepartedPlayers()} for when they disappear.
     *
     * @return the player who left, so the caller can revoke their token
     */
    public GameSessionPlayer leave(Long playerId) {
        requireOpen();
        GameSessionPlayer player = requireActiveMember(playerId);

        depart(player);

        return player;
    }

    /**
     * The host removes another player. It works exactly like that player
     * leaving.
     *
     * @return the kicked player, so the caller can revoke their token
     */
    public GameSessionPlayer kick(Long hostId, Long playerId) {
        requireOpen();
        requireActiveMember(hostId);
        requireHost(hostId, "Only the host can kick players!");

        if (hostId.equals(playerId)) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_REQUEST, "You can't kick yourself. Leave the session instead!");
        }

        GameSessionPlayer player = getActivePlayer(playerId);
        if (player == null) {
            throw new InvalidGameActionException(GameErrorCode.PLAYER_NOT_FOUND, "That player isn't in this session!");
        }

        depart(player);

        return player;
    }

    private void depart(GameSessionPlayer player) {
        player.markLeaving();

        if (player == trebek) {
            departTrebek();
            return;
        }

        // The host passes to the active player with the lowest id. Players
        // who are away come last, so nobody present is left without one
        // who can start the game or kick.
        if (player.getId().equals(hostPlayerId)) {
            passHost();
        }

        // If everyone still playing has already buzzed, nobody can buzz any
        // more, just as after the last wrong answer.
        if (state == GameSessionState.WAITING_FOR_BUZZER && haveAllPresentPlayersBuzzed()) {
            setState(GameSessionState.REVEALING_ANSWER);
        }

        // Nobody has to wait for a departed chooser to pick.
        if (state == GameSessionState.CHOOSING_CLUE) {
            handOverControlIfDeparted();
        }

        // Leaving usually doesn't change the state, so setState may not
        // have run: a player who holds no role goes now.
        removeDepartedPlayers();
    }

    /**
     * Without Trebek a game in progress can't go on, so it's over: the
     * session closes (the service finishes closing it), and Trebek stays in
     * its last broadcast. In the lobby or after the game, the host passes to
     * a player and the session is a contestant one again.
     */
    private void departTrebek() {
        if (isGameInProgress()) {
            close(SessionCloseReason.TREBEK_LEFT);
            return;
        }

        trebek = null;
        hostMode = HostMode.CONTESTANT;
        paused = false;
        passHost();
    }

    private void passHost() {
        hostPlayerId = getActivePlayers()
                .stream()
                .min(Comparator.comparing(GameSessionPlayer::isAway)
                        .thenComparing(GameSessionPlayer::getId))
                .map(GameSessionPlayer::getId)
                .orElse(null);
    }

    /**
     * Whether a departure left nobody to play: nobody at all in the lobby
     * (Trebek alone can wait there for players), and no player during the
     * game. A finished game is never abandoned: leaving is how players get
     * back to the lobby, so its results stay up.
     */
    public boolean isAbandoned() {
        return switch (state) {
            case WAITING_FOR_START -> getActivePlayers().isEmpty() && getActiveTrebek() == null;
            case FINISHED_GAME, CLOSED -> false;
            default -> getActivePlayers().isEmpty();
        };
    }

    /** Ends the session for good. Nothing can happen in it afterwards. */
    public void close(SessionCloseReason reason) {
        // Not through setState: the last broadcast shows everyone who was
        // still there, including departed players who held a role.
        state = GameSessionState.CLOSED;
        closeReason = reason;
        clearPhaseTimer();
    }

    /**
     * Every state change goes through here except closing. Roles only end
     * along with a state change (an answer resolved, the clue rounds over),
     * so departed players are removed here once they hold none. Change the
     * chooser before the state change that goes with it.
     */
    private void setState(GameSessionState newState) {
        state = newState;
        phaseNumber++;
        if (newState == GameSessionState.FINISHED_GAME) {
            // There's no clock to stop any more.
            paused = false;
        }
        removeDepartedPlayers();
    }

    /**
     * Removes every player who has left and no longer holds a role. A player
     * who leaves while choosing or answering keeps that role (the timers act
     * for them) until it passes to someone else, so the game never has a
     * missing chooser or answerer. Removing a player removes every trace of
     * them, so each player id in a response belongs to someone in it.
     *
     * <p>From the Final Jeopardy reveal on nobody is removed: everyone who
     * played stays in the results, marked as leaving if they left.
     */
    private void removeDepartedPlayers() {
        if (state == GameSessionState.FINAL_JEOPARDY_REVEALING || state == GameSessionState.FINISHED_GAME) {
            return;
        }

        List<GameSessionPlayer> departed = players.stream()
                .filter(GameSessionPlayer::isLeaving)
                .filter(player -> !holdsRole(player.getId()))
                .toList();

        for (GameSessionPlayer player : departed) {
            Long playerId = player.getId();

            players.remove(player);
            playersAlreadyBuzzed.remove(playerId);
            clueAnswers.removeIf(answer -> answer.playerId().equals(playerId));
            finalJeopardy.remove(playerId);
            // Never the chooser: they hold a role in the clue rounds, and
            // there is none from Final Jeopardy on.
        }
    }

    /**
     * A chooser who left loses control once it's time to pick: it goes to
     * the active player with the lowest score (the lowest id on a tie).
     */
    private void handOverControlIfDeparted() {
        GameSessionPlayer chooser = getPlayer(playerChoosingClue);

        if (chooser != null && chooser.isLeaving()) {
            playerChoosingClue = lowestScoringActivePlayer(playerChoosingClue);
            // The new chooser gets the full time to pick.
            phaseNumber++;
        }
    }

    // The active player with the lowest score, the lowest id on a tie, or
    // the fallback if nobody is active. Players who are away come last, so
    // nobody waits for one to pick unless everyone is away.
    private Long lowestScoringActivePlayer(Long fallback) {
        return getActivePlayers()
                .stream()
                .min(Comparator.comparing(GameSessionPlayer::isAway)
                        .thenComparingInt(GameSessionPlayer::getScore)
                        .thenComparing(GameSessionPlayer::getId))
                .map(GameSessionPlayer::getId)
                .orElse(fallback);
    }

    // Choosing and answering are the only roles, and only in the clue rounds
    // (a clue round's intro included: its chooser is already set).
    private boolean holdsRole(Long playerId) {
        return switch (state) {
            case ROUND_INTRO, CHOOSING_CLUE, CLUE_PICKED, REVEALING_DAILY_DOUBLE, WAITING_FOR_DAILY_DOUBLE_WAGER, REVEALING_CLUE,
                 WAITING_FOR_BUZZER, WAITING_FOR_ANSWER, WAITING_FOR_RULING, REVEALING_ANSWER ->
                    playerId.equals(playerChoosingClue) || playerId.equals(getPlayerAnswering());
            default -> false;
        };
    }

    // -------------------------------------------------------------------------
    // Clue rounds
    // -------------------------------------------------------------------------

    public void startGame(Long playerId) {
        requireState(GameSessionState.WAITING_FOR_START, "Game has already started!");
        requireActiveMember(playerId);
        requireHost(playerId, "Only the host can start the game!");

        if (hostMode == HostMode.TREBEK) {
            // The first player to join after Trebek chooses first.
            playerChoosingClue = getActivePlayers()
                    .stream()
                    .map(GameSessionPlayer::getId)
                    .min(Comparator.naturalOrder())
                    .orElseThrow(() -> new InvalidGameActionException(GameErrorCode.INVALID_STATE, "Nobody is playing yet!"));
        } else {
            // The host, who is starting the game, chooses first.
            playerChoosingClue = hostPlayerId;
        }
        enterRound(board.getFirstRound());
    }

    // Every round opens with its title card.
    private void enterRound(GameRound newRound) {
        round = newRound;

        if (newRound == GameRound.FINAL_JEOPARDY) {
            // Nobody chooses from here on.
            playerChoosingClue = null;
        }
        setState(GameSessionState.ROUND_INTRO);
    }

    /** The round's title card has been shown: the round begins. */
    public void finishRoundIntro() {
        requireState(GameSessionState.ROUND_INTRO);

        if (round == GameRound.FINAL_JEOPARDY) {
            startFinalJeopardy();
        } else {
            handOverControlIfDeparted();
            setState(GameSessionState.CHOOSING_CLUE);
        }
    }

    /**
     * The chooser picks a clue. In a Trebek session Trebek may pick it for
     * them (one they named out loud); the chooser keeps control either way.
     */
    public void chooseClue(Long playerId, Long clueId) {
        boolean byTrebek = isTrebek(playerId);

        if (!byTrebek && chosenBy != null && chosenBy != ChosenBy.CHOOSER && playerId.equals(playerChoosingClue)
                && (state == GameSessionState.CLUE_PICKED
                    || state == GameSessionState.REVEALING_DAILY_DOUBLE
                    || state == GameSessionState.WAITING_FOR_DAILY_DOUBLE_WAGER
                    || state == GameSessionState.REVEALING_CLUE)) {
            throw tooLate(chosenBy == ChosenBy.TIMER
                    ? "Time's up: a clue was chosen for you!"
                    : "Trebek chose a clue for you!");
        }
        requireState(GameSessionState.CHOOSING_CLUE, "You can't choose a clue right now!");
        if (!byTrebek) {
            requireActivePlayer(playerId);
            if (!playerChoosingClue.equals(playerId)) {
                throw new InvalidGameActionException(GameErrorCode.NOT_YOUR_TURN, "You can't choose a clue right now!");
            }
        }
        if (!board.getClueIds(round).contains(clueId)) {
            throw new InvalidGameActionException(GameErrorCode.CLUE_NOT_FOUND, "That's not one of the available clues!");
        }
        if (playedClues.contains(clueId)) {
            throw new InvalidGameActionException(GameErrorCode.CLUE_ALREADY_PLAYED, "That clue was already chosen!");
        }

        playClue(clueId, byTrebek ? ChosenBy.TREBEK : ChosenBy.CHOOSER);
    }

    // In a Trebek session the clue waits for Trebek to show it.
    private void playClue(Long clueId, ChosenBy by) {
        chosenClueId = clueId;
        chosenBy = by;
        dailyDoubleWager = null;
        playedClues.add(clueId);
        clueAnswers.clear();
        if (hostMode == HostMode.TREBEK) {
            setState(GameSessionState.CLUE_PICKED);
        } else {
            showClue();
        }
    }

    private void showClue() {
        if (board.isDailyDouble(chosenClueId)) {
            // Only the chooser can answer a Daily Double, so they count as the buzzer.
            playerWhoBuzzed = playerChoosingClue;
            setState(GameSessionState.REVEALING_DAILY_DOUBLE);
        } else {
            setState(GameSessionState.REVEALING_CLUE);
        }
    }

    /** The Daily Double has been announced: the chooser wagers. */
    public void finishRevealingDailyDouble() {
        requireState(GameSessionState.REVEALING_DAILY_DOUBLE);

        setState(GameSessionState.WAITING_FOR_DAILY_DOUBLE_WAGER);
    }

    /**
     * The chooser ran out of time, so the clue is chosen for them: the
     * top-most available clue, leftmost category first. It's played exactly
     * as if they had chosen it, Daily Doubles included, and they keep control.
     * This keeps a game moving even if the chooser has left.
     */
    public void timeoutChoosingClue() {
        requireState(GameSessionState.CHOOSING_CLUE);

        chooseTopClue(ChosenBy.TIMER);
    }

    private void chooseTopClue(ChosenBy by) {
        BoardClue clue = board.getRound(round)
                .orElseThrow()
                .clues()
                .filter(candidate -> !playedClues.contains(candidate.id()))
                // A stable sort, so on the same row the leftmost category wins.
                .sorted(Comparator.comparingInt(BoardClue::row))
                .findFirst()
                .orElseThrow();

        playClue(clue.id(), by);
    }

    /**
     * Trebek puts the picked clue back before showing it: it's available
     * again, and the same chooser picks again with a fresh timer.
     */
    public void returnClue(Long trebekId) {
        requireTrebek(trebekId);
        requireState(GameSessionState.CLUE_PICKED, "There's no picked clue to put back!");

        playedClues.remove(chosenClueId);
        handOverControlIfDeparted();
        setState(GameSessionState.CHOOSING_CLUE);
    }

    /** The chooser wagers on their Daily Double; in a Trebek session Trebek may place it for them. */
    public void placeDailyDoubleWager(Long playerId, int wager) {
        requireState(GameSessionState.WAITING_FOR_DAILY_DOUBLE_WAGER, "You can't wager a bet right now!");
        if (!isTrebek(playerId)) {
            requireActivePlayer(playerId);
            if (!playerChoosingClue.equals(playerId)) {
                throw new InvalidGameActionException(GameErrorCode.NOT_YOUR_TURN, "You can't wager a bet right now!");
            }
        }
        GameSessionPlayer chooser = getPlayer(playerChoosingClue);

        int minimum = minimumDailyDoubleWager(chooser);
        int maximum = maximumDailyDoubleWager(chooser);
        if (wager < minimum || wager > maximum) {
            throw new InvalidGameActionException(
                    GameErrorCode.INVALID_WAGER,
                    "Your wager must be between " + minimum + " and " + maximum + "!"
            );
        }

        dailyDoubleWager = wager;
        setState(GameSessionState.REVEALING_CLUE);
    }

    /** The chooser didn't wager in time, so they wager the minimum. */
    public void timeoutDailyDoubleWager() {
        requireState(GameSessionState.WAITING_FOR_DAILY_DOUBLE_WAGER);

        dailyDoubleWager = minimumDailyDoubleWager(getPlayer(playerChoosingClue));
        setState(GameSessionState.REVEALING_CLUE);
    }

    /** The lowest Daily Double wager the chooser can place, while they're wagering; otherwise null. */
    public Integer getMinimumDailyDoubleWager() {
        return state == GameSessionState.WAITING_FOR_DAILY_DOUBLE_WAGER
                ? minimumDailyDoubleWager(getPlayer(playerChoosingClue))
                : null;
    }

    /** The highest Daily Double wager the chooser can place, while they're wagering; otherwise null. */
    public Integer getMaximumDailyDoubleWager() {
        return state == GameSessionState.WAITING_FOR_DAILY_DOUBLE_WAGER
                ? maximumDailyDoubleWager(getPlayer(playerChoosingClue))
                : null;
    }

    // A player may always wager up to the highest clue value in the round.
    private int maximumDailyDoubleWager(GameSessionPlayer player) {
        return Math.max(player.getScore(), board.getHighestValue(round));
    }

    // 5, unless the most they can wager is less (only on a board with very
    // low values), so there is always a wager they can place.
    private int minimumDailyDoubleWager(GameSessionPlayer player) {
        return Math.min(MINIMUM_DAILY_DOUBLE_WAGER, maximumDailyDoubleWager(player));
    }

    public void finishRevealingClue() {
        requireState(GameSessionState.REVEALING_CLUE);

        playersAlreadyBuzzed.clear();

        if (board.isDailyDouble(chosenClueId)) {
            setState(GameSessionState.WAITING_FOR_ANSWER);
        } else {
            setState(GameSessionState.WAITING_FOR_BUZZER);
        }
    }

    public void buzz(Long playerId) {
        rejectTrebek(playerId);
        // Pressed a moment after someone else (the usual buzzer race).
        if (state == GameSessionState.WAITING_FOR_ANSWER
                && !board.isDailyDouble(chosenClueId)
                && !playerId.equals(playerWhoBuzzed)) {
            throw tooLate("Someone else buzzed first!");
        }
        requireState(GameSessionState.WAITING_FOR_BUZZER, "You can't buzz right now!");
        requireActivePlayer(playerId);
        if (playersAlreadyBuzzed.contains(playerId)) {
            throw new InvalidGameActionException(GameErrorCode.NOT_YOUR_TURN, "You already buzzed!");
        }
        playerWhoBuzzed = playerId;
        playersAlreadyBuzzed.add(playerId);
        setState(GameSessionState.WAITING_FOR_ANSWER);
    }

    public void timeoutBuzzer() {
        requireState(GameSessionState.WAITING_FOR_BUZZER);

        setState(GameSessionState.REVEALING_ANSWER);
    }

    /**
     * The answering player submits their answer. In a Trebek session it
     * waits, unscored, for Trebek's ruling.
     */
    public void answerClue(Long playerId, String answer) {
        rejectTrebek(playerId);
        if (state != GameSessionState.WAITING_FOR_ANSWER) {
            rejectLateAnswer(playerId);
        }
        requireState(GameSessionState.WAITING_FOR_ANSWER, "You can't answer right now!");
        requireActivePlayer(playerId);
        // On a Daily Double the chooser counts as the buzzer (see showClue).
        if (!playerWhoBuzzed.equals(playerId)) {
            throw new InvalidGameActionException(GameErrorCode.NOT_YOUR_TURN, "You can't answer right now!");
        }

        AnswerCheck check = checkAnswer(answer);
        ClueAnswer given = new ClueAnswer(playerId, answer, check);

        if (hostMode == HostMode.TREBEK) {
            pendingAnswer = given;
            setState(GameSessionState.WAITING_FOR_RULING);
        } else {
            resolveAnswer(given);
        }
    }

    // An answer sent just after its time ran out, or after Trebek ruled
    // without it (they can't buzz again on this clue, so any such answer of
    // theirs is this one).
    private void rejectLateAnswer(Long playerId) {
        if (state == GameSessionState.WAITING_FOR_RULING
                && pendingAnswer.playerId().equals(playerId) && pendingAnswer.ranOutOfTime()) {
            throw tooLate("Time's up!");
        }

        for (ClueAnswer given : clueAnswers) {
            if (given.playerId().equals(playerId) && given.ranOutOfTime()) {
                throw tooLate(given.ruledByTrebek() ? "Trebek already ruled on your answer!" : "Time's up!");
            }
        }
    }

    public void timeoutAnswer() {
        requireState(GameSessionState.WAITING_FOR_ANSWER);

        if (hostMode == HostMode.TREBEK) {
            pendingAnswer = ClueAnswer.timedOut(playerWhoBuzzed);
            setState(GameSessionState.WAITING_FOR_RULING);
        } else {
            resolveAnswer(ClueAnswer.timedOut(playerWhoBuzzed));
        }
    }

    /**
     * Trebek rules on the answering player's answer: the one they submitted,
     * or, while they're still answering, one given out loud (typing closes).
     * It scores exactly like a checked answer.
     */
    public void rule(Long trebekId, boolean correct) {
        requireTrebek(trebekId);

        ClueAnswer submitted = switch (state) {
            case WAITING_FOR_ANSWER -> ClueAnswer.timedOut(playerWhoBuzzed);
            case WAITING_FOR_RULING -> pendingAnswer;
            default -> throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "There's no answer to rule on right now!");
        };

        pendingAnswer = null;
        resolveAnswer(ruled(submitted, correct));
    }

    /**
     * The answer as Trebek ruled it. It's checked again without the question
     * form, which is Trebek's call, so a response ruled correct can say what
     * it matched.
     */
    private ClueAnswer ruled(ClueAnswer submitted, boolean correct) {
        AnswerCheck check = submitted.ranOutOfTime()
                ? null
                : board.checkAnswer(chosenClueId, submitted.answer(), false);

        return new ClueAnswer(submitted.playerId(), submitted.answer(), check, correct);
    }

    /**
     * Applies an answer (or a timeout) to the clue in play: the answerer wins
     * or loses the stake, a correct answer takes control of the board, and
     * the clue ends unless someone else can still buzz.
     */
    private void resolveAnswer(ClueAnswer clueAnswer) {
        boolean dailyDouble = board.isDailyDouble(chosenClueId);
        GameSessionPlayer player = getPlayer(clueAnswer.playerId());
        int stake = dailyDouble ? dailyDoubleWager : board.getClue(chosenClueId).value();

        clueAnswers.add(clueAnswer);
        changeScore(player, clueAnswer.correct() ? stake : -stake);

        if (clueAnswer.correct()) {
            playerChoosingClue = clueAnswer.playerId();
        }

        if (clueAnswer.correct() || dailyDouble || haveAllPresentPlayersBuzzed()) {
            setState(GameSessionState.REVEALING_ANSWER);
        } else {
            setState(GameSessionState.WAITING_FOR_BUZZER);
        }
    }

    /**
     * Checks an answer to the clue in play (in Final Jeopardy, its clue).
     * Double and Final Jeopardy need the question form ("What is ..."); the
     * Jeopardy round doesn't.
     */
    private AnswerCheck checkAnswer(String answer) {
        return board.checkAnswer(chosenClueId, answer, round != GameRound.JEOPARDY);
    }

    // Players who have left can't buzz and players who are away won't, so
    // neither is waited for.
    private boolean haveAllPresentPlayersBuzzed() {
        return getActivePlayers()
                .stream()
                .filter(player -> !player.isAway())
                .allMatch(player -> playersAlreadyBuzzed.contains(player.getId()));
    }

    public void finishRevealingAnswer() {
        requireState(GameSessionState.REVEALING_ANSWER);

        if (!getAvailableClues().isEmpty()) {
            handOverControlIfDeparted();
            setState(GameSessionState.CHOOSING_CLUE);
            return;
        }

        board.getNextRound(round).ifPresentOrElse(
                next -> {
                    // As on the show, a later clue round starts with the
                    // lowest score in control.
                    if (next != GameRound.FINAL_JEOPARDY) {
                        playerChoosingClue = lowestScoringActivePlayer(playerChoosingClue);
                    }
                    enterRound(next);
                },
                () -> {
                    // Nobody chooses once the game is over.
                    playerChoosingClue = null;
                    setState(GameSessionState.FINISHED_GAME);
                }
        );
    }

    // -------------------------------------------------------------------------
    // Trebek
    // -------------------------------------------------------------------------

    /**
     * Trebek ends the current phase now, exactly as its timer would: ends a
     * title card, shows the picked clue, opens and closes the buzzers, locks wagers and
     * answers, goes back to the board, or takes the next Final Jeopardy
     * reveal step. Never past a ruling only Trebek can make.
     */
    public void advance(Long trebekId) {
        requireTrebek(trebekId);

        switch (state) {
            case ROUND_INTRO -> finishRoundIntro();
            case CHOOSING_CLUE -> chooseTopClue(ChosenBy.TREBEK);
            case CLUE_PICKED -> showClue();
            case REVEALING_DAILY_DOUBLE -> finishRevealingDailyDouble();
            case WAITING_FOR_DAILY_DOUBLE_WAGER -> timeoutDailyDoubleWager();
            case REVEALING_CLUE -> finishRevealingClue();
            case WAITING_FOR_BUZZER -> timeoutBuzzer();
            case WAITING_FOR_ANSWER -> timeoutAnswer();
            case WAITING_FOR_RULING ->
                    throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "Rule on the answer first!");
            case REVEALING_ANSWER -> finishRevealingAnswer();
            case FINAL_JEOPARDY_WAGERING -> timeoutFinalJeopardyWagering();
            case FINAL_JEOPARDY_ANSWERING -> timeoutFinalJeopardyAnswering();
            case FINAL_JEOPARDY_REVEALING -> takeRevealStep();
            case WAITING_FOR_START, FINISHED_GAME, CLOSED ->
                    throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "There's nothing to move on from right now!");
        }
    }

    /**
     * Trebek adds to or takes from a player's score, at any time during or
     * after the game, also for a player who left but is still listed. It
     * counts for the join score like any other change.
     */
    public void adjustScore(Long trebekId, Long playerId, int amount) {
        requireOpen();
        requireTrebek(trebekId);
        if (state == GameSessionState.WAITING_FOR_START) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "Scores can only be changed once the game has started!");
        }
        if (amount == 0 || Math.abs(amount) > MAX_SCORE_ADJUSTMENT) {
            throw new InvalidGameActionException(
                    GameErrorCode.INVALID_REQUEST,
                    "amount must be between -" + MAX_SCORE_ADJUSTMENT + " and " + MAX_SCORE_ADJUSTMENT + " and not 0"
            );
        }

        GameSessionPlayer player = getPlayer(playerId);
        if (player == null) {
            throw new InvalidGameActionException(GameErrorCode.PLAYER_NOT_FOUND, "That player isn't in this session!");
        }

        changeScore(player, amount);
    }

    /**
     * Trebek stops the clock: the phase keeps the time it has left, and a
     * phase that starts while paused doesn't count down either. Actions
     * still work.
     *
     * @return false if it was already paused (nothing changed)
     */
    public boolean pause(Long trebekId, long nowNanos) {
        requireTrebek(trebekId);
        requireGameInProgress("The clock only runs during the game!");

        if (paused) {
            return false;
        }

        pausedRemaining = getPhaseRemaining(nowNanos);
        paused = true;
        phaseTimerId++;
        return true;
    }

    /**
     * Trebek restarts the clock with the time the phase had left.
     *
     * @return false if it wasn't paused (nothing changed)
     */
    public boolean resume(Long trebekId, long nowNanos) {
        requireTrebek(trebekId);
        requireGameInProgress("The clock only runs during the game!");

        if (!paused) {
            return false;
        }

        paused = false;
        if (phaseDuration != null) {
            phaseDeadlineNanos = nowNanos + pausedRemaining.toNanos();
        }
        pausedRemaining = null;
        phaseTimerId++;
        return true;
    }

    // -------------------------------------------------------------------------
    // Final Jeopardy
    // -------------------------------------------------------------------------

    private void startFinalJeopardy() {
        BoardClue clue = board.getFinalJeopardyClue();

        // Nobody chooses from here on.
        playerChoosingClue = null;
        chosenClueId = clue.id();
        playedClues.add(clue.id());
        clueAnswers.clear();
        finalJeopardy = new FinalJeopardy();
        revealOrder = null;
        revealSteps = 0;
        revealWaitingForRuling = false;
        setState(GameSessionState.FINAL_JEOPARDY_WAGERING);
    }

    /** What has been submitted for Final Jeopardy (nothing before it starts). */
    public FinalJeopardy getFinalJeopardy() {
        return finalJeopardy;
    }

    /** See {@link FinalJeopardy#placeWager} for the allowed wagers. */
    public void placeFinalJeopardyWager(Long playerId, int wager) {
        rejectTrebek(playerId);
        requireState(GameSessionState.FINAL_JEOPARDY_WAGERING, "You can't wager on Final Jeopardy right now!");

        finalJeopardy.placeWager(requireActivePlayer(playerId), wager);
    }

    /** Players who didn't wager in time wager 0 (see {@link FinalJeopardy#getWager}). */
    public void timeoutFinalJeopardyWagering() {
        requireState(GameSessionState.FINAL_JEOPARDY_WAGERING);

        finalJeopardy.capWagers(players);
        setState(GameSessionState.FINAL_JEOPARDY_ANSWERING);
    }

    /** A player may change their answer until the answering phase ends. */
    public void answerFinalJeopardy(Long playerId, String answer) {
        rejectTrebek(playerId);
        requireState(GameSessionState.FINAL_JEOPARDY_ANSWERING, "You can't answer Final Jeopardy right now!");
        requireActivePlayer(playerId);

        AnswerCheck check = checkAnswer(answer);
        finalJeopardy.answer(new ClueAnswer(playerId, answer, check));
    }

    /**
     * Answering ends. The players are then revealed one at a time, lowest
     * score first (the lowest id on a tie).
     */
    public void timeoutFinalJeopardyAnswering() {
        requireState(GameSessionState.FINAL_JEOPARDY_ANSWERING);

        revealOrder = players.stream()
                .sorted(Comparator.comparingInt(GameSessionPlayer::getScore)
                        .thenComparing(GameSessionPlayer::getId))
                .map(GameSessionPlayer::getId)
                .toList();
        revealSteps = 0;
        revealWaitingForRuling = false;
        setState(GameSessionState.FINAL_JEOPARDY_REVEALING);
    }

    /**
     * The reveal's timer ran out, so it takes the next step, except in a
     * Trebek session at a player's award before Trebek has ruled on them,
     * where the clock waits.
     */
    public void timeoutFinalJeopardyReveal() {
        requireState(GameSessionState.FINAL_JEOPARDY_REVEALING);

        if (nextRevealAwardWaitsForRuling()) {
            revealWaitingForRuling = true;
            // A new phase without a timer, until the ruling.
            phaseNumber++;
        } else {
            takeRevealStep();
        }
    }

    /**
     * Trebek rules on one player's Final Jeopardy answer, once answering is
     * over. It can be changed until that player's points are awarded. If the
     * reveal's clock is waiting for this ruling, the award follows at once.
     */
    public void ruleFinal(Long trebekId, Long playerId, boolean correct) {
        requireTrebek(trebekId);
        requireState(GameSessionState.FINAL_JEOPARDY_REVEALING, "Final Jeopardy answers are ruled on once answering is over!");

        GameSessionPlayer player = getPlayer(playerId);
        if (player == null || !revealOrder.contains(playerId)) {
            throw new InvalidGameActionException(GameErrorCode.PLAYER_NOT_FOUND, "That player isn't in Final Jeopardy!");
        }
        if (getRevealStep(playerId) == FinalRevealStep.AWARDED) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, player.getName() + "'s points were already awarded!");
        }

        finalJeopardy.rule(ruled(finalJeopardy.answerOf(playerId), correct));

        if (revealWaitingForRuling && playerId.equals(nextRevealPlayer())) {
            takeRevealStep();
        }
    }

    /**
     * The next step of the reveal: a player's wager, their answer, then their
     * award (their result applied to their score: Trebek's ruling in a Trebek
     * session, otherwise the checked answer, where no answer counts as
     * wrong); after the last player's award, the end of the game.
     */
    private void takeRevealStep() {
        if (revealSteps == REVEAL_STEPS_PER_PLAYER * revealOrder.size()) {
            setState(GameSessionState.FINISHED_GAME);
            return;
        }

        if (isNextRevealStepAnAward()) {
            Long playerId = nextRevealPlayer();
            GameSessionPlayer player = getPlayer(playerId);

            if (hostMode == HostMode.TREBEK && finalJeopardy.rulingOf(playerId) == null) {
                throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "Rule on " + player.getName() + "'s answer first!");
            }

            int wager = finalJeopardy.getWager(playerId);
            changeScore(player, finalJeopardy.isCorrect(playerId) ? wager : -wager);
        }

        revealSteps++;
        revealWaitingForRuling = false;
        // Each step is a phase of its own on the reveal's clock.
        phaseNumber++;
    }

    private boolean isNextRevealStepAnAward() {
        return revealSteps % REVEAL_STEPS_PER_PLAYER == REVEAL_STEPS_PER_PLAYER - 1;
    }

    // The player the next step reveals, or null after the last one.
    private Long nextRevealPlayer() {
        int index = revealSteps / REVEAL_STEPS_PER_PLAYER;

        return index < revealOrder.size() ? revealOrder.get(index) : null;
    }

    private boolean nextRevealAwardWaitsForRuling() {
        Long playerId = nextRevealPlayer();

        return hostMode == HostMode.TREBEK
                && playerId != null
                && isNextRevealStepAnAward()
                && finalJeopardy.rulingOf(playerId) == null;
    }

    /** The players in the order they're revealed, from the Final Jeopardy reveal on; otherwise null. */
    public List<Long> getRevealOrder() {
        return revealOrder != null && isFinalJeopardyRevealedState() ? revealOrder : null;
    }

    /** How far a player's reveal has got, from the reveal on; otherwise null. */
    public FinalRevealStep getRevealStep(Long playerId) {
        List<Long> order = getRevealOrder();
        int index = order == null ? -1 : order.indexOf(playerId);

        if (index < 0) {
            return null;
        }

        int taken = Math.clamp(revealSteps - (long) REVEAL_STEPS_PER_PLAYER * index, 0, REVEAL_STEPS_PER_PLAYER);
        return FinalRevealStep.values()[taken];
    }

    /** Whether the reveal's clock stopped at an award, waiting for Trebek's ruling. */
    public boolean isRevealWaitingForRuling() {
        return revealWaitingForRuling;
    }

    private boolean isFinalJeopardyRevealedState() {
        return state == GameSessionState.FINAL_JEOPARDY_REVEALING
                || (state == GameSessionState.FINISHED_GAME && round == GameRound.FINAL_JEOPARDY);
    }

    // -------------------------------------------------------------------------
    // Scoring
    // -------------------------------------------------------------------------

    private void changeScore(GameSessionPlayer player, int amount) {
        changeScores(Map.of(player, amount));
    }

    /**
     * Applies score changes and keeps the join score up to date. Every score
     * change goes through here.
     * <ul>
     *     <li>A score below the join score lowers it to that score.</li>
     *     <li>When a player at or below the join score scores and nobody is
     *     at or below it afterwards, it rises to the lowest score, but never
     *     above 0.</li>
     * </ul>
     * Nothing else changes it: a low scorer leaving keeps it where it is, so
     * they can't rejoin with a better score.
     */
    private void changeScores(Map<GameSessionPlayer, Integer> changes) {
        boolean bottomScored = false;

        for (Map.Entry<GameSessionPlayer, Integer> change : changes.entrySet()) {
            GameSessionPlayer player = change.getKey();

            if (change.getValue() != 0 && player.getScore() <= joinScore) {
                bottomScored = true;
            }

            player.addScore(change.getValue());
        }

        int lowest = players.stream()
                .mapToInt(GameSessionPlayer::getScore)
                .min()
                .orElse(joinScore);

        if (lowest < joinScore) {
            joinScore = lowest;
        } else if (bottomScored && lowest > joinScore) {
            joinScore = Math.min(lowest, 0);
        }
    }

    // -------------------------------------------------------------------------
    // Away
    // -------------------------------------------------------------------------

    /**
     * Records whether a player (or Trebek) is away: they have no open
     * connection (a new player is away until they first connect, see
     * {@link #addPlayer(String, String)}). It never changes the state, so a
     * page reload can't cut a phase short; it only matters from the next
     * answer on.
     *
     * @return whether anything changed
     */
    public boolean setAway(Long playerId, boolean away) {
        GameSessionPlayer player = getActiveMember(playerId);

        if (player == null || player.isAway() == away) {
            return false;
        }

        player.setAway(away);
        return true;
    }

    /** Whether there is someone in the session and every active player, and Trebek, is away. */
    public boolean isEveryoneAway() {
        List<GameSessionPlayer> active = new ArrayList<>(getActivePlayers());
        if (getActiveTrebek() != null) {
            active.add(trebek);
        }

        return !active.isEmpty() && active.stream().allMatch(GameSessionPlayer::isAway);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private GameSessionPlayer getPlayer(Long playerId) {
        return players.stream().filter(player -> player.getId().equals(playerId)).findFirst().orElse(null);
    }

    private GameSessionPlayer getActivePlayer(Long playerId) {
        GameSessionPlayer player = getPlayer(playerId);

        return player == null || player.isLeaving() ? null : player;
    }

    // A player or Trebek.
    private GameSessionPlayer getMember(Long playerId) {
        return isTrebekId(playerId) ? trebek : getPlayer(playerId);
    }

    private GameSessionPlayer getActiveMember(Long playerId) {
        GameSessionPlayer member = getMember(playerId);

        return member == null || member.isLeaving() ? null : member;
    }

    private GameSessionPlayer getActiveTrebek() {
        return trebek == null || trebek.isLeaving() ? null : trebek;
    }

    private boolean isTrebekId(Long playerId) {
        return trebek != null && trebek.getId().equals(playerId);
    }

    // Trebek acting, who hasn't left.
    private boolean isTrebek(Long playerId) {
        return getActiveTrebek() != null && trebek.getId().equals(playerId);
    }

    /** A player who left (or never joined) can't act, even if they still hold a role. Trebek doesn't play. */
    private GameSessionPlayer requireActivePlayer(Long playerId) {
        rejectTrebek(playerId);
        GameSessionPlayer player = getActivePlayer(playerId);

        if (player == null) {
            throw new InvalidGameActionException(GameErrorCode.PLAYER_NOT_IN_SESSION, "You're not in this session!");
        }

        return player;
    }

    // A player or Trebek, for what both can do: the host's actions, leaving.
    private GameSessionPlayer requireActiveMember(Long playerId) {
        GameSessionPlayer member = getActiveMember(playerId);

        if (member == null) {
            throw new InvalidGameActionException(GameErrorCode.PLAYER_NOT_IN_SESSION, "You're not in this session!");
        }

        return member;
    }

    private void rejectTrebek(Long playerId) {
        if (isTrebek(playerId)) {
            throw new InvalidGameActionException(GameErrorCode.NOT_YOUR_TURN, "Trebek doesn't play!");
        }
    }

    // Trebek's own actions: only in a Trebek session, only by Trebek.
    private void requireTrebek(Long playerId) {
        if (hostMode != HostMode.TREBEK) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "Only a Trebek host can do that!");
        }
        requireActiveMember(playerId);
        if (!isTrebek(playerId)) {
            throw new InvalidGameActionException(GameErrorCode.NOT_HOST, "Only Trebek can do that!");
        }
    }

    private void requireHost(Long playerId, String message) {
        if (!playerId.equals(hostPlayerId)) {
            throw new InvalidGameActionException(GameErrorCode.NOT_HOST, message);
        }
    }

    // For the timers: a phase can only end while it's the current one.
    private void requireState(GameSessionState phase) {
        requireState(phase, "The game isn't in " + phase + " right now!");
    }

    private void requireState(GameSessionState expected, String message) {
        if (state != expected) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, message);
        }
    }

    // Between the start and the end of the game.
    private boolean isGameInProgress() {
        return switch (state) {
            case WAITING_FOR_START, FINISHED_GAME, CLOSED -> false;
            default -> true;
        };
    }

    private void requireGameInProgress(String message) {
        if (!isGameInProgress()) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, message);
        }
    }

    private static InvalidGameActionException tooLate(String message) {
        return new InvalidGameActionException(GameErrorCode.TOO_LATE, message);
    }

    private void requireOpen() {
        if (state == GameSessionState.CLOSED) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "This session has ended!");
        }
    }

}
