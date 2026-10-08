package com.nickneim.jeopardy.game.repository;

import java.time.LocalDate;

public record SeasonGameCount(
        String season,
        Long gameCount,
        LocalDate latestGameDate
) {}
