package com.nickneim.jeopardy.game.dto;

public record ApiErrorResponse(
        int status,
        String message
) {}