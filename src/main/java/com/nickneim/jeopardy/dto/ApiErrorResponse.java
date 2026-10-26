package com.nickneim.jeopardy.dto;

/**
 * A REST error. {@code code} is an ApiErrorCode or, for a game action error,
 * its GameErrorCode, so clients can tell errors with the same status apart
 * without reading the message.
 */
public record ApiErrorResponse(
        int status,
        String code,
        String message
) {}
