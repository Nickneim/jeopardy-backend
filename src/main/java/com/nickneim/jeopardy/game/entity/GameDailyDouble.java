package com.nickneim.jeopardy.game.entity;

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
        // The database checks the same (V3's trigger), but only on flush.
        if (clue.getCategory().getGame() != game) {
            throw new IllegalArgumentException("A daily double's clue must belong to its game");
        }

        // @MapsId fills in ids that aren't generated yet when this is saved.
        this.id = new GameDailyDoubleId(game.getId(), clue.getId());
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