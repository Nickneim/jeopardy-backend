package com.nickneim.jeopardy.game.dto;

import com.nickneim.jeopardy.game.entity.Game;

import java.time.LocalDate;

/**
 * A game in the paged list. {@code clueCount} is 0 for games imported without
 * clues, which can be browsed but not played. {@code finalCategory} is the
 * name of its Final Jeopardy category, or null if it has none with a clue.
 */
public record GameSummaryResponse(
        Long id,
        String title,
        LocalDate gameDate,
        String season,
        long clueCount,
        String finalCategory
) {

    /**
     * {@code clueCount} and {@code finalCategory} are read separately, so
     * listing never loads categories or clues.
     */
    public static GameSummaryResponse from(Game game, long clueCount, String finalCategory) {
        return new GameSummaryResponse(
            game.getId(),
            game.getTitle(),
            game.getGameDate(),
            game.getSeason(),
            clueCount,
            finalCategory
        );
    }
}
