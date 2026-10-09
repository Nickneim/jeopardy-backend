package com.nickneim.jeopardy.session.model;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;

import java.util.*;

public class GameSession {

    private final UUID id;
    private final GameBoard board;
    private final List<GameSessionPlayer> players = new ArrayList<>();
    private final GameSessionTimeouts timeouts;

    private GameRound round;
    private GameSessionState state;
    private long version;

    private Long playerChoosingClue;
    private Long chosenClueId;

    private final Set<Long> playersAlreadyBuzzed = new HashSet<>();
    // Every clue chosen so far, across all rounds. Which clues are still
    // available is derived from this and the board, never stored.
    private final Set<Long> playedClues = new HashSet<>();
    private Long playerWhoBuzzed;
    // Answers to the clue in play, in order. Cleared when the next clue is
    // chosen and when Final Jeopardy starts, so it never holds stale entries.
    private final List<ClueAnswer> clueAnswers = new ArrayList<>();

    // Final Jeopardy submissions, keyed by player id. They stay secret until
    // FINAL_JEOPARDY_REVEALING; see GameSessionResponse.
    private final Map<Long, Integer> finalJeopardyWagers = new HashMap<>();
    private final Map<Long, String> finalJeopardyAnswers = new HashMap<>();

    public GameSession(UUID id, GameBoard board) {
        this.id = id;
        this.board = board;
        this.timeouts = GameSessionTimeouts.defaults();
        this.round = board.getFirstRound();
        this.state = GameSessionState.WAITING_FOR_START;
    }

    public UUID getId() {
        return id;
    }

    public GameBoard getBoard() {
        return board;
    }

    public List<GameSessionPlayer> getPlayers() {
        return List.copyOf(players);
    }

    public GameSessionTimeouts getTimeouts() { return timeouts; }

    public GameRound getRound() {
        return round;
    }

    public GameSessionState getState() {
        return state;
    }

    public Long getPlayerChoosingClue() {
        return playerChoosingClue;
    }

    public Long getChosenClueId() {
        return chosenClueId;
    }

    public Set<Long> getPlayersAlreadyBuzzed() {
        return Set.copyOf(playersAlreadyBuzzed);
    }

    /** Clues of the current round that haven't been played yet. */
    public Set<Long> getAvailableClues() {
        Set<Long> available = new HashSet<>(board.getClueIds(round));
        available.removeAll(playedClues);
        return Set.copyOf(available);
    }

    public Set<Long> getPlayedClues() {
        return Set.copyOf(playedClues);
    }

    public Long getPlayerWhoBuzzed() {
        return playerWhoBuzzed;
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
        if (chosenClueId == null || !board.isDailyDouble(chosenClueId)) {
            return null;
        }

        return switch (state) {
            case REVEALING_CLUE, WAITING_FOR_ANSWER, REVEALING_ANSWER ->
                    getPlayer(playerWhoBuzzed).getWager();
            default -> null;
        };
    }

    public void addPlayer(GameSessionPlayer player) {
        players.add(player);
    }

    /**
     * Adds a new player numbered after the highest id in this session
     * (1, 2, 3, ...). Small ids are safe as JavaScript numbers; they are
     * only unique within the session, which is fine because a player is
     * always identified by their session and token, never their id alone.
     */
    public GameSessionPlayer addPlayer(String name) {
        long nextId = players.stream()
                .mapToLong(GameSessionPlayer::getId)
                .max()
                .orElse(0) + 1;

        GameSessionPlayer player = new GameSessionPlayer(nextId, name);
        players.add(player);

        return player;
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

    public void startGame(Long playerId) {
        if (!state.equals(GameSessionState.WAITING_FOR_START)) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "Game has already started!");
        }
        if (getPlayer(playerId) == null) {
            throw new InvalidGameActionException(GameErrorCode.PLAYER_NOT_IN_SESSION, "You're not in this session!");
        }

        playerChoosingClue = players.getFirst().getId();
        enterRound(board.getFirstRound());
    }

    private void enterRound(GameRound newRound) {
        round = newRound;

        if (newRound == GameRound.FINAL_JEOPARDY) {
            startFinalJeopardy();
        } else {
            state = GameSessionState.CHOOSING_CLUE;
        }
    }

    public void chooseClue(Long playerId, Long clueId) {
        if (!state.equals(GameSessionState.CHOOSING_CLUE)) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "You can't choose a clue right now!");
        }
        if (!playerChoosingClue.equals(playerId)) {
            throw new InvalidGameActionException(GameErrorCode.NOT_YOUR_TURN, "You can't choose a clue right now!");
        }
        if (!board.getClueIds(round).contains(clueId)) {
            throw new InvalidGameActionException(GameErrorCode.CLUE_NOT_FOUND, "That's not one of the available clues!");
        }
        if (playedClues.contains(clueId)) {
            throw new InvalidGameActionException(GameErrorCode.CLUE_ALREADY_PLAYED, "That clue was already chosen!");
        }

        chosenClueId = clueId;
        playedClues.add(clueId);
        clueAnswers.clear();
        if (board.isDailyDouble(clueId)) {
            // Only the chooser can answer a Daily Double, so they count as the buzzer.
            playerWhoBuzzed = playerId;
            startWaitingForDailyDoubleWager();
        } else {
            startRevealingClue();
        }
    }

    private void startWaitingForDailyDoubleWager() {
        state = GameSessionState.WAITING_FOR_DAILY_DOUBLE_WAGER;
    }

    public void placeDailyDoubleWager(Long playerId, int wager) {
        if (!state.equals(GameSessionState.WAITING_FOR_DAILY_DOUBLE_WAGER)) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "You can't wager a bet right now!");
        }
        if (!playerChoosingClue.equals(playerId)) {
            throw new InvalidGameActionException(GameErrorCode.NOT_YOUR_TURN, "You can't wager a bet right now!");
        }

        // A player may always wager up to the highest clue value in the round.
        int maximumWager = Math.max(
                getPlayer(playerId).getScore(),
                board.getHighestValue(round)
        );

        if (wager < 5 || wager > maximumWager) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_WAGER, "You can't wager that amount!");
        }

        getPlayer(playerId).setWager(wager);
        startRevealingClue();
    }

    public void timeoutDailyDoubleWager() {
        getPlayer(playerChoosingClue).setWager(5);
        startRevealingClue();
    }

    private void startRevealingClue() {
        state = GameSessionState.REVEALING_CLUE;
    }

    public void finishRevealingClue() {
        playersAlreadyBuzzed.clear();

        if (board.isDailyDouble(chosenClueId)) {
            state = GameSessionState.WAITING_FOR_ANSWER;
        } else {
            state = GameSessionState.WAITING_FOR_BUZZER;
        }
    }

    public void buzz(Long playerId) {
        if (!state.equals(GameSessionState.WAITING_FOR_BUZZER)) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "You can't buzz right now!");
        }
        if (playersAlreadyBuzzed.contains(playerId)) {
            throw new InvalidGameActionException(GameErrorCode.NOT_YOUR_TURN, "You already buzzed!");
        }
        if (players.stream().noneMatch(player -> player.getId().equals(playerId))) {
            throw new InvalidGameActionException(GameErrorCode.PLAYER_NOT_IN_SESSION, "You're not in this session!");
        }
        playerWhoBuzzed = playerId;
        playersAlreadyBuzzed.add(playerId);
        state = GameSessionState.WAITING_FOR_ANSWER;
    }

    private void startRevealingAnswer() {
        state = GameSessionState.REVEALING_ANSWER;
    }

    public void finishRevealingAnswer() {
        if (!getAvailableClues().isEmpty()) {
            state = GameSessionState.CHOOSING_CLUE;
            return;
        }

        board.getNextRound(round).ifPresentOrElse(
                this::enterRound,
                () -> state = GameSessionState.FINISHED_GAME
        );
    }

    // -------------------------------------------------------------------------
    // Final Jeopardy
    // -------------------------------------------------------------------------

    private void startFinalJeopardy() {
        BoardClue clue = getFinalJeopardyClue();

        chosenClueId = clue.id();
        playedClues.add(clue.id());
        clueAnswers.clear();
        finalJeopardyWagers.clear();
        finalJeopardyAnswers.clear();
        state = GameSessionState.FINAL_JEOPARDY_WAGERING;
    }

    /**
     * Wagers go from 0 up to the player's score, so a player with a score of
     * 0 or less can only wager 0 but still plays. A player may change their
     * wager until the wagering phase ends.
     */
    public void placeFinalJeopardyWager(Long playerId, int wager) {
        if (state != GameSessionState.FINAL_JEOPARDY_WAGERING) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "You can't wager on Final Jeopardy right now!");
        }

        GameSessionPlayer player = getPlayer(playerId);
        if (player == null) {
            throw new InvalidGameActionException(GameErrorCode.PLAYER_NOT_IN_SESSION, "You're not in this session!");
        }

        int maximumWager = Math.max(0, player.getScore());
        if (wager < 0 || wager > maximumWager) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_WAGER, "You can't wager that amount!");
        }

        finalJeopardyWagers.put(playerId, wager);
    }

    /** Players who didn't wager in time wager 0. */
    public void timeoutFinalJeopardyWagering() {
        for (GameSessionPlayer player : players) {
            finalJeopardyWagers.putIfAbsent(player.getId(), 0);
        }

        state = GameSessionState.FINAL_JEOPARDY_ANSWERING;
    }

    /** A player may change their answer until the answering phase ends. */
    public void answerFinalJeopardy(Long playerId, String answer) {
        if (state != GameSessionState.FINAL_JEOPARDY_ANSWERING) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "You can't answer Final Jeopardy right now!");
        }
        if (getPlayer(playerId) == null) {
            throw new InvalidGameActionException(GameErrorCode.PLAYER_NOT_IN_SESSION, "You're not in this session!");
        }

        finalJeopardyAnswers.put(playerId, answer);
    }

    public void timeoutFinalJeopardyAnswering() {
        state = GameSessionState.FINAL_JEOPARDY_REVEALING;
    }

    /** Applies every wager (no answer counts as wrong) and ends the game. */
    public void finishFinalJeopardyReveal() {
        for (GameSessionPlayer player : players) {
            int wager = getFinalJeopardyWager(player.getId());

            player.addScore(isFinalJeopardyAnswerCorrect(player.getId()) ? wager : -wager);
        }

        state = GameSessionState.FINISHED_GAME;
    }

    /** The Final Jeopardy category, or null if the board has none. */
    public BoardCategory getFinalJeopardyCategory() {
        return board.getRound(GameRound.FINAL_JEOPARDY)
                .flatMap(finalRound -> finalRound.categories()
                        .stream()
                        .filter(category -> !category.clues().isEmpty())
                        .findFirst())
                .orElse(null);
    }

    public BoardClue getFinalJeopardyClue() {
        BoardCategory category = getFinalJeopardyCategory();

        return category == null ? null : category.clues().getFirst();
    }

    public boolean hasFinalJeopardyWager(Long playerId) {
        return finalJeopardyWagers.containsKey(playerId);
    }

    public boolean hasFinalJeopardyAnswer(Long playerId) {
        return finalJeopardyAnswers.containsKey(playerId);
    }

    public int getFinalJeopardyWager(Long playerId) {
        return finalJeopardyWagers.getOrDefault(playerId, 0);
    }

    public String getFinalJeopardyAnswer(Long playerId) {
        return finalJeopardyAnswers.get(playerId);
    }

    public boolean isFinalJeopardyAnswerCorrect(Long playerId) {
        String answer = finalJeopardyAnswers.get(playerId);

        return answer != null && isCorrectAnswer(getFinalJeopardyClue(), answer);
    }

    private void startWaitingForBuzzer() {
        state = GameSessionState.WAITING_FOR_BUZZER;
    }

    public void timeoutBuzzer() {
        startRevealingAnswer();
    }

    public void timeoutAnswer() {
        GameSessionPlayer player = getPlayer(playerWhoBuzzed);
        clueAnswers.add(new ClueAnswer(playerWhoBuzzed, null, false));
        if (board.isDailyDouble(chosenClueId)) {
            player.addScore(-player.getWager());
            startRevealingAnswer();
            return;
        }

        BoardClue clue = board.getClue(chosenClueId);
        player.addScore(-clue.value());
        if (players.size() == playersAlreadyBuzzed.size()) {
            startRevealingAnswer();
        } else {
            startWaitingForBuzzer();
        }
    }

    private boolean isCorrectAnswer(BoardClue clue, String answer) {
        return clue.answer().equals(answer);
    }

    private GameSessionPlayer getPlayer(Long playerId) {
        return players.stream().filter(player -> player.getId().equals(playerId)).findFirst().orElse(null);
    }

    public void answerClue(Long playerId, String answer) {
        if (!state.equals(GameSessionState.WAITING_FOR_ANSWER)) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "You can't answer right now!");
        }

        BoardClue clue = board.getClue(chosenClueId);
        int clueValue;
        if (board.isDailyDouble(chosenClueId)) {
            // player must be playerChoosingClue
            // use dailyDoubleWager
            if (!playerChoosingClue.equals(playerId)) {
                throw new InvalidGameActionException(GameErrorCode.NOT_YOUR_TURN, "You can't answer right now!");
            }
            clueValue = getPlayer(playerId).getWager();
        } else {
            if (!playerWhoBuzzed.equals(playerId)) {
                throw new InvalidGameActionException(GameErrorCode.NOT_YOUR_TURN, "You can't answer right now!");
            }
            clueValue = clue.value();
        }
        GameSessionPlayer player = getPlayer(playerId);
        boolean correct = isCorrectAnswer(clue, answer);
        clueAnswers.add(new ClueAnswer(playerId, answer, correct));
        if (correct) {
            player.addScore(clueValue);
            playerChoosingClue = playerId;
            startRevealingAnswer();
        } else {
            player.addScore(-clueValue);
            if (board.isDailyDouble(chosenClueId)) {
                startRevealingAnswer();
            } else if (players.size() == playersAlreadyBuzzed.size()) {
                startRevealingAnswer();
            } else {
                startWaitingForBuzzer();
            }
        }
    }

}