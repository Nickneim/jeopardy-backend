package com.nickneim.jeopardy.game.dto;

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
}
