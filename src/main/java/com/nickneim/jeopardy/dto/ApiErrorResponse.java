package com.nickneim.jeopardy.dto;

public record ApiErrorResponse(
        int status,
        String message
) {}