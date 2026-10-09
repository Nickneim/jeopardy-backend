package com.nickneim.jeopardy.game.repository;

import com.nickneim.jeopardy.game.entity.Game;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface GameRepository extends JpaRepository<Game, Long>, JpaSpecificationExecutor<Game> {

    @Query("""
            SELECT new com.nickneim.jeopardy.game.repository.SeasonGameCount(
                g.season, COUNT(g), MAX(g.gameDate)
            )
            FROM Game g
            GROUP BY g.season
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
}
