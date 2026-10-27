package com.nickneim.jeopardy.game.service;

import com.nickneim.jeopardy.dto.SortDirection;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Seasons are strings: mostly numbers, plus specials like "jm" and "pcj".
 * The lists that can be sorted by season (GET /api/games and the lobby)
 * share {@link #order}. GET /api/games/seasons orders numbered seasons the
 * same way but specials by their latest game (GameService).
 */
public final class Seasons {

    private static final Pattern NUMBERED_SEASON = Pattern.compile("[0-9]+");

    private Seasons() {}

    /**
     * The season's number, or null for a special season. The database's
     * {@code game.season_number} (V4) is the same.
     */
    public static BigInteger number(String season) {
        return NUMBERED_SEASON.matcher(season).matches() ? new BigInteger(season) : null;
    }

    /**
     * Numbered seasons numerically, then specials by name (case-insensitive).
     * The direction applies to both, but specials come after the numbered
     * seasons either way, as in GET /api/games/seasons. GameSpecifications
     * sorts the same way in the database.
     */
    public static Comparator<String> order(SortDirection direction) {
        return Comparator
                .comparing(Seasons::number, Comparator.nullsLast(direction.apply(Comparator.<BigInteger>naturalOrder())))
                .thenComparing(season -> season.toLowerCase(Locale.ROOT), direction.apply(Comparator.<String>naturalOrder()));
    }
}
