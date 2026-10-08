package com.nickneim.jeopardy.session.dto;

import jakarta.validation.constraints.NotNull;

public record ChooseClueRequest(
        @NotNull Long clueId
) {}
