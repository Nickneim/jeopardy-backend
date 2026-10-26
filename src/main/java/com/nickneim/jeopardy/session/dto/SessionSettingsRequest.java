package com.nickneim.jeopardy.session.dto;

import jakarta.validation.constraints.NotNull;

/** The host's lobby settings, sent with the update-settings action. */
public record SessionSettingsRequest(
        // Whether players can join after the game has started.
        @NotNull(message = "must not be null") Boolean allowLateJoin
) {}
