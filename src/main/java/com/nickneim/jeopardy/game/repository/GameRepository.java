package com.nickneim.jeopardy.game.repository;

import com.nickneim.jeopardy.game.entity.Game;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameRepository extends JpaRepository<Game, Long> {

    Page<Game> findBySeason(String season, Pageable page);
}