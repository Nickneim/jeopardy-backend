package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.model.GameSessionPlayer;

/**
 * The host running the show in a Trebek session, as everyone sees them.
 * Their id is also {@code hostPlayerId}; they have no score. Like
 * PlayerResponse, it never holds the token.
 */
public record TrebekResponse(
        Long id,
        String name,
        boolean away
) {

    /** Returns null without a Trebek. */
    public static TrebekResponse from(GameSessionPlayer trebek) {
        return trebek == null ? null : new TrebekResponse(trebek.getId(), trebek.getName(), trebek.isAway());
    }
}
