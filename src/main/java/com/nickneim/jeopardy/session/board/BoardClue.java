package com.nickneim.jeopardy.session.board;

import com.nickneim.jeopardy.game.entity.ClueType;

import java.util.Objects;

/**
 * A clue as it is played in a live session. The value is stored rather than
 * derived, so boards that don't come from the database can set their own,
 * but it can't be negative. The row is the clue's 1-based position in its
 * category, at most {@link #MAX_ROW}; a category can have gaps, e.g. no clue
 * on row 3. A text clue has no media URL and any other type must have one,
 * as in the database. {@link GameBoard} also rejects an answer no response
 * could match.
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

    /**
     * The highest row number a clue can have. Every broadcast sends each
     * category one slot per row up to the round's highest row, so this
     * bounds its size.
     * Stored games use rows 1 to 5.
     */
    public static final int MAX_ROW = 10;

    public BoardClue {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(answer, "answer");
        Objects.requireNonNull(clueType, "clueType");

        if (row < 1 || row > MAX_ROW) {
            throw new InvalidBoardException(
                    "clue " + id + " is on row " + row + ", outside 1 to " + MAX_ROW
            );
        }

        if (value < 0) {
            throw new InvalidBoardException("clue " + id + " has a negative value: " + value);
        }

        if ((clueType == ClueType.TEXT) != (mediaUrl == null)) {
            throw new InvalidBoardException(
                    "a " + clueType + " clue " + (mediaUrl == null ? "needs" : "can't have") + " a media URL: " + id
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
