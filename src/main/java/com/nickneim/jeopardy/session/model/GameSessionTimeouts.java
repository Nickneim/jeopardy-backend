package com.nickneim.jeopardy.session.model;

import java.time.Duration;

/**
 * How long each timed phase of a session lasts. Every session has its own,
 * starting from {@link #defaults()}; the host can change them before the game
 * starts. Requests are validated against {@link #MIN_MILLIS} and
 * {@link #MAX_MILLIS} (see SessionTimersRequest).
 */
public record GameSessionTimeouts(
        Duration clueChoice,
        Duration clueReveal,
        Duration dailyDoubleWager,
        Duration buzzer,
        Duration answer,
        Duration answerReveal,
        Duration finalJeopardyWager,
        Duration finalJeopardyAnswer,
        Duration finalJeopardyReveal
) {
    /** The shortest a phase can be set to: 1 second. */
    public static final long MIN_MILLIS = 1_000;

    /** The longest a phase can be set to: 5 minutes, so an absent player can't stall a game for long. */
    public static final long MAX_MILLIS = 300_000;

    public static GameSessionTimeouts defaults() {
        return new GameSessionTimeouts(
                Duration.ofSeconds(30),
                Duration.ofSeconds(3),
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                // Long enough to type an answer, on a phone too.
                Duration.ofSeconds(15),
                Duration.ofSeconds(3),
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                Duration.ofSeconds(10)
        );
    }
}
