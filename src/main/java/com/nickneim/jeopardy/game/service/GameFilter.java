package com.nickneim.jeopardy.game.service;

import com.nickneim.jeopardy.exception.InvalidParameterException;

import java.time.LocalDate;

import static com.nickneim.jeopardy.dto.QueryParameters.trimToNull;

/**
 * The optional filters of GET /api/games. A null field doesn't filter.
 * season and title are trimmed, and a blank one is treated as null. from and
 * to are inclusive.
 * playable true keeps only games with at least one clue, false only games
 * without any.
 */
public record GameFilter(
        String season,
        String title,
        LocalDate from,
        LocalDate to,
        Boolean playable
) {

    public static final GameFilter NONE = new GameFilter(null, null, null, null, null);

    public GameFilter {
        season = trimToNull(season);
        title = trimToNull(title);

        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidParameterException("'from' must not be after 'to'");
        }
    }
}
