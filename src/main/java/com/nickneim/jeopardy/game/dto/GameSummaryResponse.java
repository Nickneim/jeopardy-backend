package com.nickneim.jeopardy.game.dto;

import com.nickneim.jeopardy.game.entity.Game;

import java.time.LocalDate;

public record GameSummaryResponse(
        Long id,
        String title,
        LocalDate gameDate,
        String season
) {

    public static GameSummaryResponse from(Game game) {
        return new GameSummaryResponse(
            game.getId(),
            game.getTitle(),
            game.getGameDate(),
            game.getSeason()
        );
    }
}