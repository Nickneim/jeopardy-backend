package com.nickneim.jeopardy.session.dto;

public record JoinSessionRequest(
        Long playerId,
        String playerName
) {
}
