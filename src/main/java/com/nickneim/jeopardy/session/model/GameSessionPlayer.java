package com.nickneim.jeopardy.session.model;

public class GameSessionPlayer {

    private final Long id;
    private final String name;
    private int score;
    private int wager;

    public GameSessionPlayer(Long id, String name) {
        this.id = id;
        this.name = name;
        this.score = 0;
        this.wager = 0;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
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
