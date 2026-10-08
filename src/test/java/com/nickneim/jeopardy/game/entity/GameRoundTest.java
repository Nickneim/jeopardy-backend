package com.nickneim.jeopardy.game.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameRoundTest {

    @Test
    void fromColumn_columns1To6_areJeopardy() {
        assertEquals(GameRound.JEOPARDY, GameRound.fromColumn((short) 1));
        assertEquals(GameRound.JEOPARDY, GameRound.fromColumn((short) 6));
    }

    @Test
    void fromColumn_columns7To12_areDoubleJeopardy() {
        assertEquals(GameRound.DOUBLE_JEOPARDY, GameRound.fromColumn((short) 7));
        assertEquals(GameRound.DOUBLE_JEOPARDY, GameRound.fromColumn((short) 12));
    }

    @Test
    void fromColumn_column13_isFinalJeopardy() {
        assertEquals(GameRound.FINAL_JEOPARDY, GameRound.fromColumn((short) 13));
    }

    @Test
    void fromColumn_rejectsColumnAfterFinalJeopardy() {
        assertThrows(
                IllegalArgumentException.class,
                () -> GameRound.fromColumn((short) 14)
        );
    }
}
