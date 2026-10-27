package com.nickneim.jeopardy.game.service;

import com.nickneim.jeopardy.dto.QueryParameters;
import com.nickneim.jeopardy.dto.SortDirection;
import com.nickneim.jeopardy.exception.InvalidParameterException;

/**
 * The order of GET /api/games, applied to every matching game before
 * paging. Ties are broken by id, in the same direction.
 */
public record GameSort(Field field, SortDirection direction) {

    public static final GameSort DEFAULT = new GameSort(Field.GAME_DATE, SortDirection.DESC);

    public enum Field {
        // Case-insensitive.
        TITLE("title"),
        // Seasons.order: numbered seasons numerically, then specials by name.
        SEASON("season"),
        GAME_DATE("gameDate"),
        CLUE_COUNT("clueCount");

        private final String parameterValue;

        Field(String parameterValue) {
            this.parameterValue = parameterValue;
        }

        public String parameterValue() {
            return parameterValue;
        }
    }

    /**
     * The {@code sort} and {@code direction} parameters, either of which may
     * be missing.
     *
     * @throws InvalidParameterException for an unknown value
     */
    public static GameSort parse(String sort, String direction) {
        Field field = QueryParameters.parse(
                "sort", sort, Field.values(), Field::parameterValue, DEFAULT.field());

        return new GameSort(field, SortDirection.parse(direction, field == DEFAULT.field()));
    }
}
