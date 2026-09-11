package com.nickneim.jeopardy.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "game_daily_double")
public class GameDailyDouble {

    @EmbeddedId
    private GameDailyDoubleId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("gameId")
    @JoinColumn(name = "game_id")
    private Game game;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("clueId")
    @JoinColumn(name = "clue_id")
    private Clue clue;

    protected GameDailyDouble() {
    }

    public GameDailyDouble(Game game, Clue clue) {
        this.game = game;
        this.clue = clue;
    }

    public GameDailyDoubleId getId() {
        return id;
    }

    public Game getGame() {
        return game;
    }

    public Clue getClue() {
        return clue;
    }
}