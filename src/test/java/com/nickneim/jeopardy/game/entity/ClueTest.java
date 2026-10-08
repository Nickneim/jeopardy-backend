package com.nickneim.jeopardy.game.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class ClueTest {

    private final Game game = new Game(
            "Show #123",
            LocalDate.of(2025, 1, 1),
            "40"
    );

    @Test
    void getValue_inJeopardy_is100PerRow() {
        assertEquals(100, clueAt(1, 1).getValue());
        assertEquals(500, clueAt(6, 5).getValue());
    }

    @Test
    void getValue_inDoubleJeopardy_is200PerRow() {
        assertEquals(200, clueAt(7, 1).getValue());
        assertEquals(400, clueAt(7, 2).getValue());
        assertEquals(1000, clueAt(12, 5).getValue());
    }

    @Test
    void getValue_inFinalJeopardy_isFlat2000() {
        assertEquals(2000, clueAt(13, 1).getValue());
    }

    @Test
    void isFinalClue_isTrueOnlyForColumn13() {
        assertTrue(clueAt(13, 1).isFinalClue());
        assertFalse(clueAt(12, 1).isFinalClue());
    }

    private Clue clueAt(int column, int row) {
        Category category = new Category(
                "CATEGORY",
                (short) column,
                null,
                game
        );

        return new Clue(
                "Question",
                "Answer",
                (short) row,
                ClueType.TEXT,
                null,
                category
        );
    }
}
