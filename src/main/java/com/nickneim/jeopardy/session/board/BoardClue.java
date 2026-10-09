package com.nickneim.jeopardy.session.board;

import com.nickneim.jeopardy.game.entity.ClueType;

import java.util.Objects;

/**
 * A clue as it is played in a live session. The value is stored rather than
 * derived, so boards that don't come from the database can set their own.
 * The row is the clue's 1-based position in its category; a category can
 * have gaps, e.g. no clue on row 3. A text clue has no media URL and any
 * other type must have one, as in the database.
 */
public record BoardClue(
        Long id,
        int row,
        String question,
        String answer,
        int value,
        boolean dailyDouble,
        ClueType clueType,
        String mediaUrl
) {

    public BoardClue {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(answer, "answer");
        Objects.requireNonNull(clueType, "clueType");

        if (row < 1) {
            throw new IllegalArgumentException("Row must be at least 1: " + row);
        }

        if ((clueType == ClueType.TEXT) != (mediaUrl == null)) {
            throw new IllegalArgumentException(
                    "A " + clueType + " clue " + (mediaUrl == null ? "needs" : "can't have") + " a media URL: " + id
            );
        }
    }

    /** A plain text clue. */
    public BoardClue(
            Long id,
            int row,
            String question,
            String answer,
            int value,
            boolean dailyDouble
    ) {
        this(id, row, question, answer, value, dailyDouble, ClueType.TEXT, null);
    }
}
