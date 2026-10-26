package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.exception.GameErrorCode;

public record GameSessionErrorResponse(
        GameErrorCode code,
        String message
) {}
