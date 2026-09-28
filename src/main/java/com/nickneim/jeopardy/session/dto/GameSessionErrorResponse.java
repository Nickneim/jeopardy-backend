package com.nickneim.jeopardy.session.dto;

public record GameSessionErrorResponse(
        String code,
        String message
) {}
