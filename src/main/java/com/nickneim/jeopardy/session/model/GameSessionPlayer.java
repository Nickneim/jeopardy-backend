package com.nickneim.jeopardy.session.model;

import java.util.Objects;

public class GameSessionPlayer {

    private final Long id;
    private final String name;
    // Proves who this player is (see PlayerTokens). Only ever sent to the
    // player themselves; never add it to PlayerResponse or any other broadcast.
    private final String token;
    private int score;
    private boolean leaving;

    public GameSessionPlayer(Long id, String name, String token) {
        this(id, name, 0, token);
    }

    public GameSessionPlayer(Long id, String name, int score, String token) {
        this.id = id;
        this.name = name;
        this.token = Objects.requireNonNull(token, "token");
        this.score = score;
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

    /**
     * True once the player has left or been kicked. They can't act any more
     * and are removed from the session as soon as they hold no role.
     */
    public boolean isLeaving() {
        return leaving;
    }

    public void markLeaving() {
        leaving = true;
    }
}
