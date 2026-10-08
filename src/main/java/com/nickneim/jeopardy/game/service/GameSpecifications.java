package com.nickneim.jeopardy.game.service;

import com.nickneim.jeopardy.game.entity.Game;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class GameSpecifications {

    private static final char LIKE_ESCAPE = '\\';

    private GameSpecifications() {}

    static Specification<Game> matching(GameFilter filter) {
        List<Specification<Game>> specifications = new ArrayList<>();

        if (filter.season() != null) {
            specifications.add(hasSeason(filter.season()));
        }

        if (filter.title() != null) {
            specifications.add(titleContains(filter.title()));
        }

        if (filter.from() != null) {
            specifications.add(playedOnOrAfter(filter.from()));
        }

        if (filter.to() != null) {
            specifications.add(playedOnOrBefore(filter.to()));
        }

        return Specification.allOf(specifications);
    }

    private static Specification<Game> hasSeason(String season) {
        return (game, query, builder) ->
                builder.equal(game.get("season"), season);
    }

    // Case-insensitive substring match. %, _ and \ in the search text match
    // themselves instead of acting as wildcards.
    private static Specification<Game> titleContains(String text) {
        String pattern = "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%";

        return (game, query, builder) ->
                builder.like(builder.lower(game.get("title")), pattern, LIKE_ESCAPE);
    }

    private static Specification<Game> playedOnOrAfter(LocalDate from) {
        return (game, query, builder) ->
                builder.greaterThanOrEqualTo(game.get("gameDate"), from);
    }

    private static Specification<Game> playedOnOrBefore(LocalDate to) {
        return (game, query, builder) ->
                builder.lessThanOrEqualTo(game.get("gameDate"), to);
    }

    private static String escapeLike(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
