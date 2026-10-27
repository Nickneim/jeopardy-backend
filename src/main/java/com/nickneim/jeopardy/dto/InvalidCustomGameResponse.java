package com.nickneim.jeopardy.dto;

import com.nickneim.jeopardy.session.custom.CustomGameProblem;

import java.util.List;

/**
 * The 422 INVALID_CUSTOM_GAME error: an {@link ApiErrorResponse} with every
 * problem that keeps the custom show from being played.
 */
public record InvalidCustomGameResponse(
        int status,
        String code,
        String message,
        List<CustomGameProblem> problems
) {}
