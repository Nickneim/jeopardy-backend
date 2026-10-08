package com.nickneim.jeopardy.session.model;

import com.nickneim.jeopardy.session.security.PlayerTokens;

public class GameSessionPlayer {

    private final Long id;
    private final String name;
    // Proves who this player is. Only ever sent to the player themselves;
    // never add it to PlayerResponse or any other broadcast.
    private final String token;
    private int score;
    private int wager;

    public GameSessionPlayer(Long id, String name) {
        this.id = id;
        this.name = name;
        this.token = PlayerTokens.generate();
        this.score = 0;
        this.wager = 0;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getToken() {
        return token;
    }

    public int getScore() {
        return score;
    }

    public void addScore(int amount) {
        score += amount;
    }

    public int getWager() {
        return wager;
    }

    public void setWager(int wager) {
        this.wager = wager;
    }
}
