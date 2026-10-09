package com.nickneim.jeopardy.game.service;

import com.nickneim.jeopardy.game.exception.InvalidGameFilterException;

import java.time.LocalDate;

/**
 * The optional filters of GET /api/games. A null field doesn't filter, and a
 * blank title is treated as null. from and to are inclusive. playable true
 * keeps only games with at least one clue, false only games without any.
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
        if (title != null && title.isBlank()) {
            title = null;
        }

        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidGameFilterException("'from' must not be after 'to'");
        }
    }
}
