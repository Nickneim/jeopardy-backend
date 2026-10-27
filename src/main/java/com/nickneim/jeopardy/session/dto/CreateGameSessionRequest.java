package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.exception.InvalidParameterException;
import jakarta.validation.Valid;

/**
 * Either a stored game or a custom show: exactly one of the two is set
 * ({@link #requireOneSource}). Timers and other settings are set by the host
 * in the lobby, not here.
 */
public record CreateGameSessionRequest(
        Long gameId,
        @Valid CustomGameRequest customGame
) {

    /** A request for a stored game. */
    public CreateGameSessionRequest(Long gameId) {
        this(gameId, null);
    }

    /** A request for a custom show. */
    public CreateGameSessionRequest(CustomGameRequest customGame) {
        this(null, customGame);
    }

    /** @throws InvalidParameterException unless exactly one of gameId and customGame is set */
    public void requireOneSource() {
        if ((gameId == null) == (customGame == null)) {
            throw new InvalidParameterException("Exactly one of gameId and customGame must be set");
        }
    }
}
