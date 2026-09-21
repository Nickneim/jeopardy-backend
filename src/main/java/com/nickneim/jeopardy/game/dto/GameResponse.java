package com.nickneim.jeopardy.game.dto;

import com.nickneim.jeopardy.game.entity.Category;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.entity.GameDailyDouble;
import com.nickneim.jeopardy.game.service.GameService;

import java.time.LocalDate;
import java.util.List;

public record GameResponse(
        Long id,
        String title,
        LocalDate gameDate,
        String season,
        List<GameCategoryResponse> jeopardyRound,
        List<GameCategoryResponse> doubleJeopardyRound,
        GameCategoryResponse finalJeopardy,

        List<Long> dailyDoubleClueIds
) {

    public static GameResponse from(Game game) {

        List<GameCategoryResponse> jeopardyRound = game.getCategories().stream().filter(Category::isJeopardyRound).map(GameCategoryResponse::from).toList();
        List<GameCategoryResponse> doubleJeopardyRound = game.getCategories().stream().filter(Category::isDoubleJeopardyRound).map(GameCategoryResponse::from).toList();
        GameCategoryResponse finalJeopardy = game.getCategories().stream().filter(Category::isFinalJeopardy).map(GameCategoryResponse::from).findFirst().orElse(null);

        List<Long> dailyDoubleClueIds = game.getDailyDoubles().stream().map(
                gameDailyDouble -> { return gameDailyDouble.getClue().getId(); }
        ).toList();

        return new GameResponse(
                game.getId(),
                game.getTitle(),
                game.getGameDate(),
                game.getSeason(),
                jeopardyRound,
                doubleJeopardyRound,
                finalJeopardy,
                dailyDoubleClueIds
        );
    }
}
