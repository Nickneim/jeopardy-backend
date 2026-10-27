package com.nickneim.jeopardy.game.repository;

import com.nickneim.jeopardy.game.entity.Game;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface GameRepository extends JpaRepository<Game, Long>, JpaSpecificationExecutor<Game> {

    /**
     * Every season with its game count. Seasons that differ only in case are
     * one season, shown in lowercase, as the case-insensitive season filter
     * of GET /api/games treats them.
     */
    @Query("""
            SELECT new com.nickneim.jeopardy.game.repository.SeasonGameCount(
                LOWER(g.season), COUNT(g), MAX(g.gameDate)
            )
            FROM Game g
            GROUP BY LOWER(g.season)
            """)
    List<SeasonGameCount> findSeasonGameCounts();

    /**
     * How many clues each of the given games has, counted in the database.
     * Games without clues are left out of the result.
     */
    @Query("""
            SELECT new com.nickneim.jeopardy.game.repository.GameClueCount(
                category.game.id, COUNT(clue)
            )
            FROM Clue clue
            JOIN clue.category category
            WHERE category.game.id IN :gameIds
            GROUP BY category.game.id
            """)
    List<GameClueCount> countCluesByGameIds(Collection<Long> gameIds);

    /**
     * The Final Jeopardy category (column 13) of each of the given games,
     * counting only one that has a clue, as a session's board does. Games
     * without one are left out of the result.
     */
    @Query("""
            SELECT DISTINCT new com.nickneim.jeopardy.game.repository.GameFinalCategory(
                category.game.id, category.name
            )
            FROM Clue clue
            JOIN clue.category category
            WHERE category.game.id IN :gameIds
            AND category.columnNumber = 13
            """)
    List<GameFinalCategory> findFinalCategoriesByGameIds(Collection<Long> gameIds);
}
