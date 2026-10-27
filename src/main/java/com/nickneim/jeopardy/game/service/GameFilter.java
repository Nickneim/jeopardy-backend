package com.nickneim.jeopardy.game.service;

import com.nickneim.jeopardy.exception.InvalidParameterException;

import java.time.LocalDate;

import static com.nickneim.jeopardy.dto.QueryParameters.trimToNull;

/**
 * The optional filters of GET /api/games. A null field doesn't filter.
 * season, title and finalCategory are trimmed, and a blank one is treated as
 * null. from and to are inclusive.
 * playable true keeps only games with at least one clue, false only games
 * without any. finalCategory keeps games whose Final Jeopardy category (one
 * with a clue) contains it.
 */
public record GameFilter(
        String season,
        String title,
        LocalDate from,
        LocalDate to,
        Boolean playable,
        String finalCategory
) {

    public static final GameFilter NONE = new GameFilter(null, null, null, null, null, null);

    public GameFilter {
        season = trimToNull(season);
        title = trimToNull(title);
        finalCategory = trimToNull(finalCategory);

        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidParameterException("'from' must not be after 'to'");
        }
    }

    /** Filters without one on the Final Jeopardy category. */
    public GameFilter(String season, String title, LocalDate from, LocalDate to, Boolean playable) {
        this(season, title, from, to, playable, null);
    }
}
