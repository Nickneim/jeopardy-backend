package com.nickneim.jeopardy.session.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

// Explicit messages aren't localized, so error responses read the same
// whatever the client's Accept-Language.
public record CreateGameSessionRequest(
        @NotNull(message = "must not be null") Long gameId,
        // Optional; any duration left out keeps its default.
        @Valid SessionTimersRequest timers
) {
}
