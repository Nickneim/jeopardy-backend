package com.nickneim.jeopardy.game.repository;

import com.nickneim.jeopardy.game.entity.Game;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

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
}
