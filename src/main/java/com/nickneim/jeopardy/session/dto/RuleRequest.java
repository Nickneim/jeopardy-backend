package com.nickneim.jeopardy.session.dto;

import jakarta.validation.constraints.NotNull;

/** Trebek's ruling on the answering player's answer. */
public record RuleRequest(
        @NotNull(message = "must not be null") Boolean correct
) {}
