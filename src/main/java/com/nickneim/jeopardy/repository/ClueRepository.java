package com.nickneim.jeopardy.repository;

import com.nickneim.jeopardy.entity.Clue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClueRepository extends JpaRepository<Clue, Long> {
}