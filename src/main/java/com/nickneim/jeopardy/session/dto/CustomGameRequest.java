package com.nickneim.jeopardy.session.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A custom show a host built in the browser, to create a session from. Only
 * its shape is validated here (a 400 when it's wrong, a client bug); whether
 * it can be played, such as every clue having an answer, is up to
 * {@code CustomGameBoards}, which reports every problem at once (422).
 * Explicit messages aren't localized, so errors read the same whatever the
 * client's Accept-Language.
 */
public record CustomGameRequest(
        String name,
        @NotNull(message = "must not be null")
        @Size(max = MAX_CATEGORIES, message = "must have at most " + MAX_CATEGORIES + " categories")
        List<@NotNull(message = "must not be null") @Valid CustomCategoryRequest> jeopardyRound,
        @NotNull(message = "must not be null")
        @Size(max = MAX_CATEGORIES, message = "must have at most " + MAX_CATEGORIES + " categories")
        List<@NotNull(message = "must not be null") @Valid CustomCategoryRequest> doubleJeopardyRound,
        // Null for a show without Final Jeopardy.
        CustomFinalJeopardyRequest finalJeopardy
) {
    public static final int MAX_CATEGORIES = 6;
}
