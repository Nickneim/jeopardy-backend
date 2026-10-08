package com.nickneim.jeopardy.session.model;

import com.nickneim.jeopardy.game.entity.GameRound;
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

    private Long playerChoosingClue;
    private Long chosenClueId;

    private final Set<Long> playersAlreadyBuzzed = new HashSet<>();
    // Every clue chosen so far, across all rounds. Which clues are still
    // available is derived from this and the board, never stored.
    private final Set<Long> playedClues = new HashSet<>();
    private Long playerWhoBuzzed;

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

    public void startGame(Long playerId) {
        if (!state.equals(GameSessionState.WAITING_FOR_START)) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "Game has already started!");
        }
        if (getPlayer(playerId) == null) {
            throw new InvalidGameActionException(GameErrorCode.PLAYER_NOT_IN_SESSION, "You're not in this session!");
        }

        round = board.getFirstRound();
        playerChoosingClue = players.getFirst().getId();
        state = GameSessionState.CHOOSING_CLUE;

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
        state = GameSessionState.CHOOSING_CLUE;

        if (getAvailableClues().isEmpty()) {
            board.getNextRound(round).ifPresentOrElse(
                    nextRound -> round = nextRound,
                    () -> state = GameSessionState.FINISHED_GAME
            );
        }
    }

    private void startWaitingForBuzzer() {
        state = GameSessionState.WAITING_FOR_BUZZER;
    }

    public void timeoutBuzzer() {
        startRevealingAnswer();
    }

    public void timeoutAnswer() {
        GameSessionPlayer player = getPlayer(playerWhoBuzzed);
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
        if (isCorrectAnswer(clue, answer)) {
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