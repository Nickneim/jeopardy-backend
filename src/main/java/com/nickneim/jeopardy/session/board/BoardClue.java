package com.nickneim.jeopardy.session.board;

import java.util.Objects;

/**
 * A clue as it is played in a live session. The value is stored rather than
 * derived, so boards that don't come from the database can set their own.
 * The row is the clue's 1-based position in its category; a category can
 * have gaps, e.g. no clue on row 3.
 */
public record BoardClue(
        Long id,
        int row,
        String question,
        String answer,
        int value,
        boolean dailyDouble
) {

    public BoardClue {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(answer, "answer");

        if (row < 1) {
            throw new IllegalArgumentException("Row must be at least 1: " + row);
        }
    }
}
