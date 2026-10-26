package com.nickneim.jeopardy.session.model;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.answer.AnswerCheck;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.GameBoard;
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

    private static final AtomicLong INSTANCE_IDS = new AtomicLong();

    private final SessionCode id;
    // Tells this session apart from any other that has (or had) its id: a
    // closed session's id can be reused, and player ids restart at 1.
    private final long instanceId = INSTANCE_IDS.incrementAndGet();
    private final GameBoard board;
    private final Instant createdAt;
    // Every player still in the session, in join order. A player who left
    // while holding a role stays here, marked as leaving, until the role ends.
    private final List<GameSessionPlayer> players = new ArrayList<>();
    private GameSessionTimeouts timeouts;

    // Ids are never reused, so a removed player's id can't come back as
    // someone else's.
    private long nextPlayerId = 1;
    private Long hostPlayerId;
    private boolean allowLateJoin = true;
    private int joinScore = 0;

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

    private Long playerChoosingClue;
    private Long chosenClueId;
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

    private FinalJeopardy finalJeopardy = new FinalJeopardy();

    public GameSession(SessionCode id, GameBoard board) {
        this(id, board, GameSessionTimeouts.defaults(), Instant.now());
    }

    public GameSession(SessionCode id, GameBoard board, GameSessionTimeouts timeouts, Instant createdAt) {
        this.id = id;
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

    public GameBoard getBoard() {
        return board;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Every player in the session, including those waiting to be removed. */
    public List<GameSessionPlayer> getPlayers() {
        return List.copyOf(players);
    }

    /** Players who haven't left. */
    public List<GameSessionPlayer> getActivePlayers() {
        return players.stream()
                .filter(player -> !player.isLeaving())
                .toList();
    }

    /** The host, or null while nobody has joined. */
    public Long getHostPlayerId() {
        return hostPlayerId;
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

    /** The player who must answer right now, or null if nobody is answering. */
    public Long getPlayerAnswering() {
        return state == GameSessionState.WAITING_FOR_ANSWER ? playerWhoBuzzed : null;
    }

    /**
     * Players who already buzzed on the clue in play and can't buzz again.
     * Empty outside the buzzing and answering phases, because the underlying
     * set is only reset when the next clue's reveal finishes.
     */
    public Set<Long> getPlayersLockedOut() {
        if (state == GameSessionState.WAITING_FOR_BUZZER
                || state == GameSessionState.WAITING_FOR_ANSWER) {
            return Set.copyOf(playersAlreadyBuzzed);
        }

        return Set.of();
    }

    /**
     * Every answer given to the current clue (or, until the next clue is
     * chosen, the last one played), oldest first. Empty during Final
     * Jeopardy, which has its own reveal.
     */
    public List<ClueAnswer> getClueAnswers() {
        return List.copyOf(clueAnswers);
    }

    /** The wager on the Daily Double in play once it is placed, otherwise null. */
    public Integer getDailyDoubleWager() {
        return switch (state) {
            case REVEALING_CLUE, WAITING_FOR_ANSWER, REVEALING_ANSWER -> dailyDoubleWager;
            default -> null;
        };
    }

    /** Adds a player with a given id. The first player to join becomes the host. */
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

    /** Records that the current phase ends {@code duration} after {@code nowNanos}. */
    public void startPhaseTimer(Duration duration, long nowNanos) {
        phaseDuration = duration;
        phaseDeadlineNanos = nowNanos + duration.toNanos();
        phaseTimerId++;
    }

    /** Records that the current phase has no timer. */
    public void clearPhaseTimer() {
        phaseDuration = null;
        phaseTimerId++;
    }

    /** Identifies the phase timer armed last; see {@link #startPhaseTimer}. */
    public long getPhaseTimerId() {
        return phaseTimerId;
    }

    /** The full length of the current phase, or null if it has no timer. */
    public Duration getPhaseDuration() {
        return phaseDuration;
    }

    /**
     * How long the current phase has left at {@code nowNanos} (never
     * negative), or null if it has no timer.
     */
    public Duration getPhaseRemaining(long nowNanos) {
        if (phaseDuration == null) {
            return null;
        }

        return Duration.ofNanos(Math.max(0, phaseDeadlineNanos - nowNanos));
    }

    // -------------------------------------------------------------------------
    // Lobby: host, timers, leaving
    // -------------------------------------------------------------------------

    /** Only the host can change the timers, and only before the game starts. */
    public void updateTimeouts(Long playerId, GameSessionTimeouts newTimeouts) {
        requireActivePlayer(playerId);
        requireHost(playerId, "Only the host can change the timers!");

        if (state != GameSessionState.WAITING_FOR_START) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "Timers can only be changed before the game starts!");
        }

        timeouts = newTimeouts;
    }

    /**
     * Only the host can block or allow joining after the start, and only
     * before the game starts. Joining the lobby is always allowed.
     */
    public void updateLateJoin(Long playerId, boolean allowed) {
        requireActivePlayer(playerId);
        requireHost(playerId, "Only the host can change the settings!");

        if (state != GameSessionState.WAITING_FOR_START) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "Settings can only be changed before the game starts!");
        }

        allowLateJoin = allowed;
    }

    /**
     * The player leaves the session. They can't act any more, but see
     * {@link #removeDepartedPlayers()} for when they disappear.
     *
     * @return the player who left, so the caller can revoke their token
     */
    public GameSessionPlayer leave(Long playerId) {
        requireOpen();
        GameSessionPlayer player = requireActivePlayer(playerId);

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
        requireActivePlayer(hostId);
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

        // The host is always the active player with the lowest id.
        if (player.getId().equals(hostPlayerId)) {
            hostPlayerId = getActivePlayers()
                    .stream()
                    .map(GameSessionPlayer::getId)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
        }

        // If everyone still playing has already buzzed, nobody can buzz any
        // more, just as after the last wrong answer.
        if (state == GameSessionState.WAITING_FOR_BUZZER && haveAllActivePlayersBuzzed()) {
            startRevealingAnswer();
        }

        // Leaving usually doesn't change the state, so setState may not
        // have run: a player who holds no role goes now.
        removeDepartedPlayers();
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
        removeDepartedPlayers();
    }

    /**
     * Removes every player who has left and no longer holds a role. A player
     * who leaves while choosing or answering keeps that role (the timers act
     * for them) until it passes to someone else, so the game never has a
     * missing chooser or answerer. Removing a player removes every trace of
     * them, so each player id in a response belongs to someone in it.
     */
    private void removeDepartedPlayers() {
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

            // Only possible once the rounds with a chooser are over.
            if (playerId.equals(playerChoosingClue)) {
                playerChoosingClue = null;
            }
        }
    }

    // Choosing and answering are the only roles, and only in the clue rounds.
    private boolean holdsRole(Long playerId) {
        return switch (state) {
            case CHOOSING_CLUE, WAITING_FOR_DAILY_DOUBLE_WAGER, REVEALING_CLUE,
                 WAITING_FOR_BUZZER, WAITING_FOR_ANSWER, REVEALING_ANSWER ->
                    playerId.equals(playerChoosingClue) || playerId.equals(getPlayerAnswering());
            default -> false;
        };
    }

    // -------------------------------------------------------------------------
    // Clue rounds
    // -------------------------------------------------------------------------

    public void startGame(Long playerId) {
        if (!state.equals(GameSessionState.WAITING_FOR_START)) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "Game has already started!");
        }
        requireActivePlayer(playerId);
        requireHost(playerId, "Only the host can start the game!");

        // The host is the first player to join who is still here.
        playerChoosingClue = hostPlayerId;
        enterRound(board.getFirstRound());
    }

    private void enterRound(GameRound newRound) {
        round = newRound;

        if (newRound == GameRound.FINAL_JEOPARDY) {
            startFinalJeopardy();
        } else {
            setState(GameSessionState.CHOOSING_CLUE);
        }
    }

    public void chooseClue(Long playerId, Long clueId) {
        if (!state.equals(GameSessionState.CHOOSING_CLUE)) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "You can't choose a clue right now!");
        }
        requireActivePlayer(playerId);
        if (!playerChoosingClue.equals(playerId)) {
            throw new InvalidGameActionException(GameErrorCode.NOT_YOUR_TURN, "You can't choose a clue right now!");
        }
        if (!board.getClueIds(round).contains(clueId)) {
            throw new InvalidGameActionException(GameErrorCode.CLUE_NOT_FOUND, "That's not one of the available clues!");
        }
        if (playedClues.contains(clueId)) {
            throw new InvalidGameActionException(GameErrorCode.CLUE_ALREADY_PLAYED, "That clue was already chosen!");
        }

        playClue(clueId);
    }

    private void playClue(Long clueId) {
        chosenClueId = clueId;
        dailyDoubleWager = null;
        playedClues.add(clueId);
        clueAnswers.clear();
        if (board.isDailyDouble(clueId)) {
            // Only the chooser can answer a Daily Double, so they count as the buzzer.
            playerWhoBuzzed = playerChoosingClue;
            startWaitingForDailyDoubleWager();
        } else {
            startRevealingClue();
        }
    }

    /**
     * The chooser ran out of time, so the clue is chosen for them: the
     * top-most available clue, leftmost category first. It's played exactly
     * as if they had chosen it, Daily Doubles included, and they keep control.
     * This keeps a game moving even if the chooser has left.
     */
    public void timeoutChoosingClue() {
        requirePhase(GameSessionState.CHOOSING_CLUE);

        BoardClue clue = board.getRound(round)
                .orElseThrow()
                .clues()
                .filter(candidate -> !playedClues.contains(candidate.id()))
                // A stable sort, so on the same row the leftmost category wins.
                .sorted(Comparator.comparingInt(BoardClue::row))
                .findFirst()
                .orElseThrow();

        playClue(clue.id());
    }

    private void startWaitingForDailyDoubleWager() {
        setState(GameSessionState.WAITING_FOR_DAILY_DOUBLE_WAGER);
    }

    public void placeDailyDoubleWager(Long playerId, int wager) {
        if (!state.equals(GameSessionState.WAITING_FOR_DAILY_DOUBLE_WAGER)) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "You can't wager a bet right now!");
        }
        GameSessionPlayer player = requireActivePlayer(playerId);
        if (!playerChoosingClue.equals(playerId)) {
            throw new InvalidGameActionException(GameErrorCode.NOT_YOUR_TURN, "You can't wager a bet right now!");
        }

        if (wager < minimumDailyDoubleWager(player) || wager > maximumDailyDoubleWager(player)) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_WAGER, "You can't wager that amount!");
        }

        dailyDoubleWager = wager;
        startRevealingClue();
    }

    /** The chooser didn't wager in time, so they wager the minimum. */
    public void timeoutDailyDoubleWager() {
        requirePhase(GameSessionState.WAITING_FOR_DAILY_DOUBLE_WAGER);

        dailyDoubleWager = minimumDailyDoubleWager(getPlayer(playerChoosingClue));
        startRevealingClue();
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

    private void startRevealingClue() {
        setState(GameSessionState.REVEALING_CLUE);
    }

    public void finishRevealingClue() {
        requirePhase(GameSessionState.REVEALING_CLUE);

        playersAlreadyBuzzed.clear();

        if (board.isDailyDouble(chosenClueId)) {
            setState(GameSessionState.WAITING_FOR_ANSWER);
        } else {
            setState(GameSessionState.WAITING_FOR_BUZZER);
        }
    }

    public void buzz(Long playerId) {
        if (!state.equals(GameSessionState.WAITING_FOR_BUZZER)) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "You can't buzz right now!");
        }
        requireActivePlayer(playerId);
        if (playersAlreadyBuzzed.contains(playerId)) {
            throw new InvalidGameActionException(GameErrorCode.NOT_YOUR_TURN, "You already buzzed!");
        }
        playerWhoBuzzed = playerId;
        playersAlreadyBuzzed.add(playerId);
        setState(GameSessionState.WAITING_FOR_ANSWER);
    }

    private void startRevealingAnswer() {
        setState(GameSessionState.REVEALING_ANSWER);
    }

    public void finishRevealingAnswer() {
        requirePhase(GameSessionState.REVEALING_ANSWER);

        if (!getAvailableClues().isEmpty()) {
            setState(GameSessionState.CHOOSING_CLUE);
            return;
        }

        board.getNextRound(round).ifPresentOrElse(
                this::enterRound,
                () -> setState(GameSessionState.FINISHED_GAME)
        );
    }

    // -------------------------------------------------------------------------
    // Final Jeopardy
    // -------------------------------------------------------------------------

    private void startFinalJeopardy() {
        BoardClue clue = board.getFinalJeopardyClue();

        chosenClueId = clue.id();
        playedClues.add(clue.id());
        clueAnswers.clear();
        finalJeopardy = new FinalJeopardy();
        setState(GameSessionState.FINAL_JEOPARDY_WAGERING);
    }

    /** See {@link FinalJeopardy#placeWager} for the allowed wagers. */
    public void placeFinalJeopardyWager(Long playerId, int wager) {
        if (state != GameSessionState.FINAL_JEOPARDY_WAGERING) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "You can't wager on Final Jeopardy right now!");
        }

        finalJeopardy.placeWager(requireActivePlayer(playerId), wager);
    }

    /** Players who didn't wager in time wager 0. */
    public void timeoutFinalJeopardyWagering() {
        requirePhase(GameSessionState.FINAL_JEOPARDY_WAGERING);

        finalJeopardy.wagerZeroForMissing(players);

        setState(GameSessionState.FINAL_JEOPARDY_ANSWERING);
    }

    /** A player may change their answer until the answering phase ends. */
    public void answerFinalJeopardy(Long playerId, String answer) {
        if (state != GameSessionState.FINAL_JEOPARDY_ANSWERING) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "You can't answer Final Jeopardy right now!");
        }
        requireActivePlayer(playerId);

        AnswerCheck check = checkAnswer(answer);
        finalJeopardy.answer(new ClueAnswer(playerId, answer, check));
    }

    public void timeoutFinalJeopardyAnswering() {
        requirePhase(GameSessionState.FINAL_JEOPARDY_ANSWERING);

        setState(GameSessionState.FINAL_JEOPARDY_REVEALING);
    }

    /** Applies every wager (no answer counts as wrong) and ends the game. */
    public void finishFinalJeopardyReveal() {
        requirePhase(GameSessionState.FINAL_JEOPARDY_REVEALING);

        // Applied together, so the join score sees the final scores at once.
        changeScores(finalJeopardy.scoreChanges(players));
        setState(GameSessionState.FINISHED_GAME);
    }

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

    /** What has been submitted for Final Jeopardy (nothing before it starts). */
    public FinalJeopardy getFinalJeopardy() {
        return finalJeopardy;
    }

    private void startWaitingForBuzzer() {
        setState(GameSessionState.WAITING_FOR_BUZZER);
    }

    public void timeoutBuzzer() {
        requirePhase(GameSessionState.WAITING_FOR_BUZZER);

        startRevealingAnswer();
    }

    public void timeoutAnswer() {
        requirePhase(GameSessionState.WAITING_FOR_ANSWER);

        resolveAnswer(ClueAnswer.timedOut(playerWhoBuzzed));
    }

    /**
     * Checks an answer to the clue in play (in Final Jeopardy, its clue).
     * Double and Final Jeopardy need the question form ("What is ..."); the
     * Jeopardy round doesn't.
     */
    private AnswerCheck checkAnswer(String answer) {
        return board.checkAnswer(chosenClueId, answer, round != GameRound.JEOPARDY);
    }

    // Players who have left can't buzz, so they don't count.
    private boolean haveAllActivePlayersBuzzed() {
        return getActivePlayers()
                .stream()
                .allMatch(player -> playersAlreadyBuzzed.contains(player.getId()));
    }

    private GameSessionPlayer getPlayer(Long playerId) {
        return players.stream().filter(player -> player.getId().equals(playerId)).findFirst().orElse(null);
    }

    private GameSessionPlayer getActivePlayer(Long playerId) {
        GameSessionPlayer player = getPlayer(playerId);

        return player == null || player.isLeaving() ? null : player;
    }

    /** A player who left (or never joined) can't act, even if they still hold a role. */
    private GameSessionPlayer requireActivePlayer(Long playerId) {
        GameSessionPlayer player = getActivePlayer(playerId);

        if (player == null) {
            throw new InvalidGameActionException(GameErrorCode.PLAYER_NOT_IN_SESSION, "You're not in this session!");
        }

        return player;
    }

    private void requireHost(Long playerId, String message) {
        if (!playerId.equals(hostPlayerId)) {
            throw new InvalidGameActionException(GameErrorCode.NOT_HOST, message);
        }
    }

    // For the timers: a phase can only end while it's the current one.
    private void requirePhase(GameSessionState phase) {
        if (state != phase) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "The game isn't in " + phase + " right now!");
        }
    }

    private void requireOpen() {
        if (state == GameSessionState.CLOSED) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "This session has ended!");
        }
    }

    public void answerClue(Long playerId, String answer) {
        if (!state.equals(GameSessionState.WAITING_FOR_ANSWER)) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "You can't answer right now!");
        }
        requireActivePlayer(playerId);
        // On a Daily Double the chooser counts as the buzzer (see playClue).
        if (!playerWhoBuzzed.equals(playerId)) {
            throw new InvalidGameActionException(GameErrorCode.NOT_YOUR_TURN, "You can't answer right now!");
        }

        AnswerCheck check = checkAnswer(answer);
        resolveAnswer(new ClueAnswer(playerId, answer, check));
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

        if (clueAnswer.correct() || dailyDouble || haveAllActivePlayersBuzzed()) {
            startRevealingAnswer();
        } else {
            startWaitingForBuzzer();
        }
    }

}
