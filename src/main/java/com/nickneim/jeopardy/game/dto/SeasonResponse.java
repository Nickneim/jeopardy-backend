package com.nickneim.jeopardy.game.dto;

import com.nickneim.jeopardy.game.repository.SeasonGameCount;

public record SeasonResponse(
        String season,
        long gameCount
) {

    public static SeasonResponse from(SeasonGameCount seasonGameCount) {
        return new SeasonResponse(
            seasonGameCount.season(),
            seasonGameCount.gameCount()
        );
    }
}
