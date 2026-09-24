package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionState;

import java.util.UUID;

public record GameSessionResponse(
        UUID id,
        GameRound round,
        GameSessionState state,
        java.util.List<PlayerResponse> players
) {
    static public GameSessionResponse from(GameSession session) {
        return new GameSessionResponse(
                session.getId(),
                session.getRound(),
                session.getState(),
                session.getPlayers().stream()
                        .map(PlayerResponse::from)
                        .toList()
        );
    }
}
