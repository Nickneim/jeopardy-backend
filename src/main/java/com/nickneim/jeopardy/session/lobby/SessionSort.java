package com.nickneim.jeopardy.session.lobby;

import com.nickneim.jeopardy.dto.QueryParameters;
import com.nickneim.jeopardy.dto.SortDirection;
import com.nickneim.jeopardy.exception.InvalidParameterException;
import com.nickneim.jeopardy.game.service.Seasons;
import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import com.nickneim.jeopardy.session.model.SessionCode;

import java.util.Comparator;
import java.util.function.Function;

/**
 * The order of GET /api/game-sessions, applied to the sessions of every
 * instance before paging. Missing values sort last in both directions, and
 * ties are broken by id, in the same direction.
 */
public record SessionSort(Field field, SortDirection direction) {

    public static final SessionSort DEFAULT = new SessionSort(Field.CREATED_AT, SortDirection.DESC);

    public enum Field {
        ID("id"),
        // Case-insensitive, like hostName.
        GAME_TITLE("gameTitle"),
        // Seasons.order, as GET /api/games sorts by season.
        GAME_SEASON("gameSeason"),
        GAME_DATE("gameDate"),
        HOST_NAME("hostName"),
        PLAYER_COUNT("playerCount"),
        // SessionStage order: LOBBY first, FINISHED last.
        STAGE("stage"),
        CREATED_AT("createdAt");

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
    public static SessionSort parse(String sort, String direction) {
        Field field = QueryParameters.parse(
                "sort", sort, Field.values(), Field::parameterValue, DEFAULT.field());

        return new SessionSort(field, SortDirection.parse(direction, field == DEFAULT.field()));
    }

    public Comparator<GameSessionSummaryResponse> comparator() {
        Comparator<GameSessionSummaryResponse> byField = switch (field) {
            case ID -> (first, second) -> 0;
            case GAME_TITLE -> by(GameSessionSummaryResponse::gameTitle, String.CASE_INSENSITIVE_ORDER);
            // Seasons.order applies the direction itself.
            case GAME_SEASON -> Comparator.comparing(
                    GameSessionSummaryResponse::gameSeason,
                    Comparator.nullsLast(Seasons.order(direction))
            );
            case GAME_DATE -> by(GameSessionSummaryResponse::gameDate, Comparator.naturalOrder());
            case HOST_NAME -> by(GameSessionSummaryResponse::hostName, String.CASE_INSENSITIVE_ORDER);
            case PLAYER_COUNT -> by(GameSessionSummaryResponse::playerCount, Comparator.naturalOrder());
            case STAGE -> by(
                    summary -> SessionStage.of(summary.state(), summary.round()),
                    Comparator.<SessionStage>naturalOrder()
            );
            case CREATED_AT -> by(GameSessionSummaryResponse::createdAt, Comparator.naturalOrder());
        };

        return byField.thenComparing(
                GameSessionSummaryResponse::id,
                direction.apply(Comparator.<SessionCode>naturalOrder())
        );
    }

    private <T> Comparator<GameSessionSummaryResponse> by(
            Function<GameSessionSummaryResponse, T> key,
            Comparator<T> ascending
    ) {
        return Comparator.comparing(key, Comparator.nullsLast(direction.apply(ascending)));
    }
}
