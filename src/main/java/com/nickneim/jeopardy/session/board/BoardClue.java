package com.nickneim.jeopardy.session.board;

import java.util.Objects;

/**
 * A clue as it is played in a live session. The value is stored rather than
 * derived, so boards that don't come from the database can set their own.
 */
public record BoardClue(
        Long id,
        String question,
        String answer,
        int value,
        boolean dailyDouble
) {

    public BoardClue {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(answer, "answer");
    }
}
