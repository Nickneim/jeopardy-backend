package com.nickneim.jeopardy.dto;

import java.time.LocalDate;

public record GameSummaryResponse(
        Long id,
        String title,
        LocalDate gameDate,
        String season
) {}