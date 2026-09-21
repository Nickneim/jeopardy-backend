package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.model.GameSession;

import java.util.UUID;

public record CreateGameSessionResponse(UUID id) {

    public static CreateGameSessionResponse from(GameSession gameSession) {
        return new CreateGameSessionResponse(
            gameSession.getId()
        );
    }
}
