package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.model.GameSessionPlayer;

public record PlayerResponse(
        Long id,
        String name,
        int score
) {

    public static PlayerResponse from(GameSessionPlayer player) {
        return new PlayerResponse(
                player.getId(),
                player.getName(),
                player.getScore()
        );
    }
}
