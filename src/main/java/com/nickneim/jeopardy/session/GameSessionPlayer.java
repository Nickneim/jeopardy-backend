package com.nickneim.jeopardy.session;

public class GameSessionPlayer {

    private final Long id;
    private final String name;
    private int score;

    public GameSessionPlayer(Long id, String name) {
        this.id = id;
        this.name = name;
        this.score = 0;
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
}
