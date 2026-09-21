package com.nickneim.jeopardy.game.repository;

import com.nickneim.jeopardy.game.entity.Clue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClueRepository extends JpaRepository<Clue, Long> {
}