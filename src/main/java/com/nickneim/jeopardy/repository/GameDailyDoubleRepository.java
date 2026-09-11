package com.nickneim.jeopardy.repository;

import com.nickneim.jeopardy.entity.GameDailyDouble;
import com.nickneim.jeopardy.entity.GameDailyDoubleId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameDailyDoubleRepository
        extends JpaRepository<GameDailyDouble, GameDailyDoubleId> {
}