package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.SessionCode;

public record CreateGameSessionResponse(SessionCode id) {

    public static CreateGameSessionResponse from(GameSession gameSession) {
        return new CreateGameSessionResponse(gameSession.getId());
    }
}
