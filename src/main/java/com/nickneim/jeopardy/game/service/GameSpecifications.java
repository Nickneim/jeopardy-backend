package com.nickneim.jeopardy.game.service;

import com.nickneim.jeopardy.dto.SortDirection;
import com.nickneim.jeopardy.game.entity.Clue;
import com.nickneim.jeopardy.game.entity.Game;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Nulls;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

final class GameSpecifications {

    private static final char LIKE_ESCAPE = '\\';

    // Postgres's built-in collation that compares by Unicode code point.
    private static final String CODE_POINT_ORDER = "ucs_basic";

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

        if (filter.finalCategory() != null) {
            specifications.add(finalCategoryContains(filter.finalCategory()));
        }

        return Specification.allOf(specifications);
    }

    /**
     * Orders the query by {@code sort}, then by id in the same direction.
     * It filters nothing, and leaves the page's count query alone.
     */
    static Specification<Game> sortedBy(GameSort sort) {
        return (game, query, builder) -> {
            if (query.getResultType() == Long.class) {
                return builder.conjunction();
            }

            boolean ascending = sort.direction() == SortDirection.ASC;
            List<Order> orders = new ArrayList<>();

            switch (sort.field()) {
                // By code point, as the lobby's String.CASE_INSENSITIVE_ORDER
                // compares: the database's own collation mostly ignores spaces
                // and punctuation, so it put "Show #100 - Tuesday" before
                // "Show #10 - Monday".
                case TITLE -> orders.add(order(
                        builder,
                        ((HibernateCriteriaBuilder) builder).collate(builder.lower(game.get("title")), CODE_POINT_ORDER),
                        ascending
                ));
                // As Seasons.order: specials have no number, so they come
                // after the numbered seasons, by name.
                case SEASON -> {
                    orders.add(order(builder, game.get("seasonNumber"), ascending));
                    orders.add(order(builder, builder.lower(game.get("season")), ascending));
                }
                case GAME_DATE -> orders.add(order(builder, game.get("gameDate"), ascending));
                case CLUE_COUNT -> orders.add(order(builder, clueCount(game, query, builder), ascending));
            }

            orders.add(order(builder, game.get("id"), ascending));
            query.orderBy(orders);

            return builder.conjunction();
        };
    }

    private static Order order(CriteriaBuilder builder, Expression<?> expression, boolean ascending) {
        return ascending
                ? builder.asc(expression, Nulls.LAST)
                : builder.desc(expression, Nulls.LAST);
    }

    // A correlated subquery, so sorting by it loads no clues.
    private static Expression<Long> clueCount(Root<Game> game, CriteriaQuery<?> query, CriteriaBuilder builder) {
        Subquery<Long> count = query.subquery(Long.class);
        Root<Clue> clue = count.from(Clue.class);

        return count.select(builder.count(clue))
                .where(builder.equal(clue.get("category").get("game").get("id"), game.get("id")));
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

    // Matched like the title, against the Final Jeopardy category (column
    // 13), counting only one with a clue, as GameRepository's
    // findFinalCategoriesByGameIds does. An EXISTS subquery, like hasClues.
    private static Specification<Game> finalCategoryContains(String text) {
        String pattern = "%" + escapeLike(text) + "%";

        return (game, query, builder) -> {
            Subquery<Long> clues = query.subquery(Long.class);
            Root<Clue> clue = clues.from(Clue.class);
            Path<Object> category = clue.get("category");

            clues.select(builder.literal(1L))
                    .where(
                            builder.equal(category.get("game").get("id"), game.get("id")),
                            builder.equal(category.get("columnNumber"), (short) 13),
                            builder.like(
                                    builder.lower(category.get("name")),
                                    builder.lower(builder.literal(pattern)),
                                    LIKE_ESCAPE
                            )
                    );

            return builder.exists(clues);
        };
    }

    // Case-insensitive, like the title, so "JM" finds the "jm" season.
    private static Specification<Game> hasSeason(String season) {
        return (game, query, builder) -> builder.equal(
                builder.lower(game.get("season")),
                builder.lower(builder.literal(season))
        );
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
