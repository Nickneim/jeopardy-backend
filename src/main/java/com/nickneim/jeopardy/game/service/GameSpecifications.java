package com.nickneim.jeopardy.game.service;

import com.nickneim.jeopardy.game.entity.Clue;
import com.nickneim.jeopardy.game.entity.Game;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

        if (filter.playable() != null) {
            specifications.add(hasClues(filter.playable()));
        }

        return Specification.allOf(specifications);
    }

    // An EXISTS subquery, so no clue is loaded or counted. It also applies to
    // the page's count query, so totals only count matching games.
    private static Specification<Game> hasClues(boolean hasClues) {
        return (game, query, builder) -> {
            Subquery<Long> clues = query.subquery(Long.class);
            Root<Clue> clue = clues.from(Clue.class);

            clues.select(builder.literal(1L))
                    .where(builder.equal(
                            clue.get("category").get("game").get("id"),
                            game.get("id")
                    ));

            Predicate exists = builder.exists(clues);
            return hasClues ? exists : builder.not(exists);
        };
    }

    private static Specification<Game> hasSeason(String season) {
        return (game, query, builder) ->
                builder.equal(game.get("season"), season);
    }

    // Case-insensitive substring match. %, _ and \ in the search text match
    // themselves instead of acting as wildcards. The database lowercases both
    // sides, since its lower() and Java's toLowerCase disagree on some
    // characters (Java turns İ into two).
    private static Specification<Game> titleContains(String text) {
        String pattern = "%" + escapeLike(text) + "%";

        return (game, query, builder) -> builder.like(
                builder.lower(game.get("title")),
                builder.lower(builder.literal(pattern)),
                LIKE_ESCAPE
        );
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
