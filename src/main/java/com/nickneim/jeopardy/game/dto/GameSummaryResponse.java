package com.nickneim.jeopardy.game.dto;

import com.nickneim.jeopardy.game.entity.Game;

import java.time.LocalDate;

/**
 * A game in the paged list. {@code clueCount} is 0 for games imported without
 * clues, which can be browsed but not played.
 */
public record GameSummaryResponse(
        Long id,
        String title,
        LocalDate gameDate,
        String season,
        long clueCount
) {

    /** {@code clueCount} is counted separately, so listing never loads clues. */
    public static GameSummaryResponse from(Game game, long clueCount) {
        return new GameSummaryResponse(
            game.getId(),
            game.getTitle(),
            game.getGameDate(),
            game.getSeason(),
            clueCount
        );
    }
}
