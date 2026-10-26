package com.nickneim.jeopardy.session.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Explicit messages aren't localized, so error responses read the same
// whatever the client's Accept-Language. The name is in every broadcast, so
// its length is capped.
public record JoinSessionRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = MAX_NAME_LENGTH, message = "must be at most " + MAX_NAME_LENGTH + " characters")
        String playerName
) {
    public static final int MAX_NAME_LENGTH = 30;
}
