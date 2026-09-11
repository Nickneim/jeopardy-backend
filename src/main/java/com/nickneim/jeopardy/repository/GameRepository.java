package com.nickneim.jeopardy.repository;

import com.nickneim.jeopardy.entity.Game;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameRepository extends JpaRepository<Game, Long> {
}