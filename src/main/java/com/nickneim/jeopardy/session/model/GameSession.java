package com.nickneim.jeopardy.session.model;

import com.nickneim.jeopardy.game.entity.GameRound;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class GameSession {

    private final UUID id;
    private final List<GameSessionPlayer> players = new ArrayList<>();

    private GameRound round;
    private GameSessionState state;

    private Long playerChoosingClue;
    private Long chosenClueId;

    private final Set<Long> playersAlreadyBuzzed = new HashSet<>();
    private Long playerWhoBuzzed;

    private Instant stateStartedAt;
    private Duration stateTimeout;

    public GameSession(UUID id) {
        this.id = id;
        this.round = GameRound.JEOPARDY;
        this.state = GameSessionState.CHOOSING_CLUE;
    }

    public UUID getId() {
        return id;
    }

    public List<GameSessionPlayer> getPlayers() {
        return List.copyOf(players);
    }

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

    public Long getPlayerWhoBuzzed() {
        return playerWhoBuzzed;
    }

    public Instant getStateStartedAt() {
        return stateStartedAt;
    }

    public Duration getStateTimeout() {
        return stateTimeout;
    }

    public void addPlayer(GameSessionPlayer player) {
        players.add(player);
    }
}