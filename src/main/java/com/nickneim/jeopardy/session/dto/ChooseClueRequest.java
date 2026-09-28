package com.nickneim.jeopardy.session.dto;

import jakarta.validation.constraints.NotNull;

public record ChooseClueRequest(
        @NotNull Long playerId,
        @NotNull Long clueId
) {}
