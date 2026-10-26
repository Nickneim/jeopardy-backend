package com.nickneim.jeopardy.session.dto;

import jakarta.validation.constraints.NotNull;

// Explicit messages aren't localized, so error responses read the same
// whatever the server's default locale (STOMP has no Accept-Language).
public record ChooseClueRequest(
        @NotNull(message = "must not be null") Long clueId
) {}
