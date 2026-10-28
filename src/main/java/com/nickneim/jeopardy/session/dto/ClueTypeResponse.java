package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.game.entity.ClueType;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionState;

import java.util.List;

/**
 * A clue type the session's game has, for the host's lobby setting: how many
 * of its clues the game has, and whether they're played. TEXT is always
 * enabled.
 */
public record ClueTypeResponse(
        ClueType type,
        int clueCount,
        boolean enabled
) {

    /**
     * Every type on the session's full board, in ClueType order, while in
     * the lobby; empty once the game starts, when the types would hint at a
     * hidden Daily Double's.
     */
    public static List<ClueTypeResponse> from(GameSession session) {
        if (session.getState() != GameSessionState.WAITING_FOR_START) {
            return List.of();
        }

        return session.getFullBoard()
                .getClueTypeCounts()
                .entrySet()
                .stream()
                .map(entry -> new ClueTypeResponse(
                        entry.getKey(),
                        entry.getValue(),
                        !session.getDisabledClueTypes().contains(entry.getKey())
                ))
                .toList();
    }
}
