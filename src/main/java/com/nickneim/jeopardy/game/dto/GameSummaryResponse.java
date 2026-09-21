package com.nickneim.jeopardy.game.dto;

import java.time.LocalDate;

public record GameSummaryResponse(
        Long id,
        String title,
        LocalDate gameDate,
        String season
) {}