package com.nickneim.jeopardy.session.model;

import java.time.Duration;

public record GameSessionTimeouts(
        Duration clueReveal,
        Duration buzzer,
        Duration answer,
        Duration answerReveal
) {
    public static GameSessionTimeouts defaults() {
        return new GameSessionTimeouts(
                Duration.ofSeconds(3),
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                Duration.ofSeconds(3)
        );
    }
}