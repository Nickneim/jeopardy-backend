package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.model.GameSessionPlayer;

/**
 * A player as everyone sees them. {@code leaving} is true for a player who
 * left (or was kicked) but still holds a role, until they're removed, and
 * for everyone who left once the results are shown. {@code away} is true
 * while a player has no open connection (see GameSessionPlayer.isAway).
 */
public record PlayerResponse(
        Long id,
        String name,
        int score,
        boolean leaving,
        boolean away
) {

    public static PlayerResponse from(GameSessionPlayer player) {
        return new PlayerResponse(
                player.getId(),
                player.getName(),
                player.getScore(),
                player.isLeaving(),
                player.isAway()
        );
    }
}
