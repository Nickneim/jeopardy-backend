package com.nickneim.jeopardy.session.dto;

/**
 * Sent only to the player who joined. The token proves who they are when
 * they connect, so it must never be broadcast or shown to other players.
 */
public record JoinSessionResponse(
        Long playerId,
        String token
) {}
