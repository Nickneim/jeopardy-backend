package com.nickneim.jeopardy.game.entity;

public enum GameRound {
    JEOPARDY,
    DOUBLE_JEOPARDY,
    FINAL_JEOPARDY;

    public static GameRound fromColumn(short column) {
        if (column <= 6) {
            return JEOPARDY;
        }
        if (column <= 12) {
            return DOUBLE_JEOPARDY;
        }
        if (column == 13) {
            return FINAL_JEOPARDY;
        }

        throw new IllegalArgumentException("Invalid category column: " + column);
    }
}