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
    void getValue_inJeopardy_is200PerRow() {
        assertEquals(200, clueAt(1, 1).getValue());
        assertEquals(1000, clueAt(6, 5).getValue());
    }

    @Test
    void getValue_inDoubleJeopardy_is400PerRow() {
        assertEquals(400, clueAt(7, 1).getValue());
        assertEquals(800, clueAt(7, 2).getValue());
        assertEquals(2000, clueAt(12, 5).getValue());
    }

    @Test
    void getValue_inFinalJeopardy_isFlat2000() {
        assertEquals(2000, clueAt(13, 1).getValue());
    }

    @Test
    void getValue_rejectsColumnOutsideTheBoard() {
        assertThrows(IllegalArgumentException.class, () -> clueAt(14, 1).getValue());
        assertThrows(IllegalArgumentException.class, () -> clueAt(0, 1).getValue());
    }

    @Test
    void isFinalClue_isTrueOnlyForColumn13() {
        assertTrue(clueAt(13, 1).isFinalClue());
        assertFalse(clueAt(12, 1).isFinalClue());
    }

    @Test
    void newClue_withoutTypeIsText() {
        Category category = new Category("CATEGORY", (short) 1, null, game);

        assertEquals(
                ClueType.TEXT,
                new Clue("Question", "Answer", (short) 1, null, null, category).getClueType()
        );
    }

    @Test
    void newClue_hasMediaUrlExactlyWhenNotText() {
        Category category = new Category("CATEGORY", (short) 1, null, game);

        assertThrows(IllegalArgumentException.class, () -> new Clue(
                "Question", "Answer", (short) 1, ClueType.IMAGE, null, category));
        assertThrows(IllegalArgumentException.class, () -> new Clue(
                "Question", "Answer", (short) 1, ClueType.TEXT, "https://example.com/a.jpg", category));
        assertDoesNotThrow(() -> new Clue(
                "Question", "Answer", (short) 1, ClueType.IMAGE, "https://example.com/a.jpg", category));
    }

    @Test
    void newClue_rejectsBlankMediaUrl() {
        Category category = new Category("CATEGORY", (short) 1, null, game);

        assertThrows(IllegalArgumentException.class, () -> new Clue(
                "Question", "Answer", (short) 1, ClueType.IMAGE, "  ", category));
        assertThrows(IllegalArgumentException.class, () -> new Clue(
                "Question", "Answer", (short) 1, ClueType.IMAGE, " \t\n\r\f\u000B", category));
    }

    @Test
    void newClue_countsOnlyAsciiWhitespaceAsBlank() {
        // Like the database's check_media_url, which can't see these as space.
        Category category = new Category("CATEGORY", (short) 1, null, game);

        assertDoesNotThrow(() -> new Clue(
                "Question", "Answer", (short) 1, ClueType.IMAGE, "\u001C", category));
    }

    @Test
    void newClue_needsACategory() {
        assertThrows(NullPointerException.class, () -> new Clue(
                "Question", "Answer", (short) 1, ClueType.TEXT, null, null));
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
