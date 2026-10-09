package com.nickneim.jeopardy.session.dto;

import jakarta.validation.constraints.NotBlank;

// Explicit messages aren't localized, so error responses read the same
// whatever the client's Accept-Language.
public record JoinSessionRequest(
        @NotBlank(message = "must not be blank") String playerName
) {
}
