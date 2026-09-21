package com.nickneim.jeopardy.game.repository;

import com.nickneim.jeopardy.game.entity.GameDailyDouble;
import com.nickneim.jeopardy.game.entity.GameDailyDoubleId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameDailyDoubleRepository
        extends JpaRepository<GameDailyDouble, GameDailyDoubleId> {
}