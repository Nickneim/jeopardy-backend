package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.model.GameSession;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.Range;

/**
 * Trebek adds {@code amount} to a player's score (a negative amount takes
 * away). The model also rejects 0.
 */
public record AdjustScoreRequest(
        @NotNull(message = "must not be null") Long playerId,
        @NotNull(message = "must not be null")
        @Range(min = -GameSession.MAX_SCORE_ADJUSTMENT, max = GameSession.MAX_SCORE_ADJUSTMENT, message = MESSAGE)
        Integer amount
) {
    // Explicit, so error responses read the same whatever the Accept-Language.
    private static final String MESSAGE =
            "must be between -" + GameSession.MAX_SCORE_ADJUSTMENT + " and " + GameSession.MAX_SCORE_ADJUSTMENT;
}
