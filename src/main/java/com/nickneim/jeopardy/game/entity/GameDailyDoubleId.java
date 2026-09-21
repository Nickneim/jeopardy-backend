package com.nickneim.jeopardy.game.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class GameDailyDoubleId implements Serializable {

    @Column(name = "game_id")
    private Long gameId;

    @Column(name = "clue_id")
    private Long clueId;

    protected GameDailyDoubleId() {
    }

    public GameDailyDoubleId(Long gameId, Long clueId) {
        this.gameId = gameId;
        this.clueId = clueId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GameDailyDoubleId that = (GameDailyDoubleId) o;
        return Objects.equals(gameId, that.gameId) && Objects.equals(clueId, that.clueId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(gameId, clueId);
    }

}