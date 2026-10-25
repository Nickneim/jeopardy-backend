package com.nickneim.jeopardy.session.model;

import com.nickneim.jeopardy.session.security.PlayerTokens;

public class GameSessionPlayer {

    private final Long id;
    private final String name;
    // Proves who this player is. Only ever sent to the player themselves;
    // never add it to PlayerResponse or any other broadcast.
    private final String token;
    private int score;
    private boolean leaving;

    public GameSessionPlayer(Long id, String name) {
        this(id, name, 0);
    }

    public GameSessionPlayer(Long id, String name, int score) {
        this.id = id;
        this.name = name;
        this.token = PlayerTokens.generate();
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
