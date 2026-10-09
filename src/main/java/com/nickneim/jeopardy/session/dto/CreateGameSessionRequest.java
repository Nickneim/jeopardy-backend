package com.nickneim.jeopardy.session.dto;

import jakarta.validation.constraints.NotNull;

// Explicit messages aren't localized, so error responses read the same
// whatever the client's Accept-Language. Timers and other settings are set
// by the host in the lobby, not here.
public record CreateGameSessionRequest(
        @NotNull(message = "must not be null") Long gameId
) {
}
