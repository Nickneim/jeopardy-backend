package com.nickneim.jeopardy.session.model;

import com.nickneim.jeopardy.game.entity.Category;
import com.nickneim.jeopardy.game.entity.Clue;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;

import java.util.*;
import java.util.function.Predicate;

public class GameSession {

    private final UUID id;
    private final Game game;
    private final List<GameSessionPlayer> players = new ArrayList<>();
    private final GameSessionTimeouts timeouts;

    private GameRound round;
    private GameSessionState state;

    private Long playerChoosingClue;
    private Long chosenClueId;

    private final Set<Long> playersAlreadyBuzzed = new HashSet<>();
    private final Set<Long> availableClues = new HashSet<>();
    private final Set<Long> cluesAlreadySelected = new HashSet<>();
    private Long playerWhoBuzzed;

    public GameSession(UUID id, Game game) {
        this.id = id;
        this.game = game;
        this.timeouts = GameSessionTimeouts.defaults();
        this.round = GameRound.JEOPARDY;
        this.state = GameSessionState.WAITING_FOR_START;
    }

    public UUID getId() {
        return id;
    }

    public Game getGame() {
        return game;
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

    public Set<Long> getAvailableClues() {
        return Set.copyOf(availableClues);
    }

    public Set<Long> getCluesAlreadySelected() {
        return Set.copyOf(cluesAlreadySelected);
    }

    public Long getPlayerWhoBuzzed() {
        return playerWhoBuzzed;
    }

    public void addPlayer(GameSessionPlayer player) {
        players.add(player);
    }

    public void startGame() {
        if (!state.equals(GameSessionState.WAITING_FOR_START)) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "Game has already started!");
        }

        setUpNewRound(GameRound.JEOPARDY);
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
        if (cluesAlreadySelected.contains(clueId)) {
            throw new InvalidGameActionException(GameErrorCode.CLUE_ALREADY_PLAYED, "That clue was already chosen!");
        }
        if (!availableClues.contains(clueId)) {
            throw new InvalidGameActionException(GameErrorCode.CLUE_NOT_FOUND, "That's not one of the available clues!");
        }

        chosenClueId = clueId;
        cluesAlreadySelected.add(clueId);
        if (game.isDailyDouble(clueId)) {
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

            int maximumWager = Math.max(
                    getPlayer(playerId).getScore(),
                    round == GameRound.JEOPARDY ? 1000 : 2000
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

        if (game.isDailyDouble(chosenClueId)) {
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

        if (availableClues.size() == cluesAlreadySelected.size()) {
            if (round == GameRound.JEOPARDY) {
                setUpNewRound(GameRound.DOUBLE_JEOPARDY);
            } else if (round == GameRound.DOUBLE_JEOPARDY) {
                setUpNewRound(GameRound.FINAL_JEOPARDY);
            } else {
                state = GameSessionState.FINISHED_GAME;
            }
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
        Clue clue = getClue(chosenClueId);
        player.addScore(-clue.getValue());
        if (players.size() == playersAlreadyBuzzed.size()) {
            startRevealingAnswer();
        } else {
            startWaitingForBuzzer();
        }
    }

    private boolean isCorrectAnswer(Clue clue, String answer) {
        return clue.getAnswer().equals(answer);
    }

    private Clue getClue(Long clueId) {
        return game.getCategories().stream().flatMap(category -> category.getClues().stream()).filter(c -> c.getId().equals(clueId)).findFirst().orElse(null);
    }

    private GameSessionPlayer getPlayer(Long playerId) {
        return players.stream().filter(player -> player.getId().equals(playerId)).findFirst().orElse(null);
    }

    public void answerClue(Long playerId, String answer) {
        if (!state.equals(GameSessionState.WAITING_FOR_ANSWER)) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_STATE, "You can't answer right now!");
        }

        Clue clue = getClue(chosenClueId);
        int clueValue;
        if (game.isDailyDouble(chosenClueId)) {
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
            clueValue = clue.getValue();
        }
        GameSessionPlayer player = getPlayer(playerId);
        if (isCorrectAnswer(clue, answer)) {
            player.addScore(clueValue);
            playerChoosingClue = playerId;
            startRevealingAnswer();
        } else {
            player.addScore(-clueValue);
            if (game.isDailyDouble(chosenClueId)) {
                startRevealingAnswer();
            } else if (players.size() == playersAlreadyBuzzed.size()) {
                startRevealingAnswer();
            } else {
                startWaitingForBuzzer();
            }
        }
    }

    private void setUpNewRound(GameRound newRound) {
        availableClues.clear();
        cluesAlreadySelected.clear();
        round = newRound;
        Predicate<Category> categoryFilter = switch (newRound) {
            case JEOPARDY -> Category::isJeopardyRound;
            case DOUBLE_JEOPARDY -> Category::isDoubleJeopardyRound;
            case FINAL_JEOPARDY -> Category::isFinalJeopardy;
        };
        game.getCategories()
                .stream()
                .filter(categoryFilter)
                .flatMap(category -> category.getClues().stream())
                .forEach(
                        clue -> this.availableClues.add(clue.getId())
                );
    }

}