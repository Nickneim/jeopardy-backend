package com.nickneim.jeopardy.session.dto;

public record PlayerResponse(
        Long id,
        String name,
        int score
) {
}
