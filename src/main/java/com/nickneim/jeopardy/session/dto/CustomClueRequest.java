package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.game.entity.ClueType;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.Range;

/**
 * A clue of a custom show. The question is kept exactly as written, HTML
 * tags included, like a stored clue's. Its value follows the row, as for
 * stored games. Missing or too long text, the answer's format and the media
 * link are checked by {@code CustomGameBoards}, not here, so they're
 * reported with everything else the host can fix.
 */
public record CustomClueRequest(
        @NotNull(message = "must not be null")
        @Range(min = 1, max = MAX_ROW, message = "must be between 1 and " + MAX_ROW)
        Integer row,
        String question,
        String answer,
        @NotNull(message = "must not be null") ClueType clueType,
        String mediaUrl,
        boolean dailyDouble
) {
    /** Rows of a custom category, as in stored games. */
    public static final int MAX_ROW = 5;
}
