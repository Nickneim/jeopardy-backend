package com.nickneim.jeopardy.game.dto;

import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.entity.GameRound;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        // Categories come ordered by column, and each round keeps that order.
        Map<GameRound, List<GameCategoryResponse>> rounds = game.getCategories()
                .stream()
                .collect(Collectors.groupingBy(
                        category -> GameRound.fromColumn(category.getColumnNumber()),
                        () -> new EnumMap<>(GameRound.class),
                        Collectors.mapping(GameCategoryResponse::from, Collectors.toList())
                ));

        List<Long> dailyDoubleClueIds = game.getDailyDoubles()
                .stream()
                .map(dailyDouble -> dailyDouble.getClue().getId())
                .toList();

        return new GameResponse(
                game.getId(),
                game.getTitle(),
                game.getGameDate(),
                game.getSeason(),
                rounds.getOrDefault(GameRound.JEOPARDY, List.of()),
                rounds.getOrDefault(GameRound.DOUBLE_JEOPARDY, List.of()),
                rounds.getOrDefault(GameRound.FINAL_JEOPARDY, List.of())
                        .stream()
                        .findFirst()
                        .orElse(null),
                dailyDoubleClueIds
        );
    }
}
