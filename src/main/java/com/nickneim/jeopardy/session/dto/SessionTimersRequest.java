package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.model.GameSessionTimeouts;
import org.hibernate.validator.constraints.Range;

import java.time.Duration;

/**
 * Phase durations in milliseconds, for the host's update-timers action in
 * the lobby. Every field is optional: one that is left out (or null) keeps
 * its current value, which until the host changes it is the default.
 */
public record SessionTimersRequest(
        @Range(min = MIN, max = MAX, message = MESSAGE) Long clueChoiceMs,
        @Range(min = MIN, max = MAX, message = MESSAGE) Long clueRevealMs,
        @Range(min = MIN, max = MAX, message = MESSAGE) Long dailyDoubleWagerMs,
        @Range(min = MIN, max = MAX, message = MESSAGE) Long buzzerMs,
        @Range(min = MIN, max = MAX, message = MESSAGE) Long answerMs,
        @Range(min = MIN, max = MAX, message = MESSAGE) Long answerRevealMs,
        @Range(min = MIN, max = MAX, message = MESSAGE) Long finalJeopardyWagerMs,
        @Range(min = MIN, max = MAX, message = MESSAGE) Long finalJeopardyAnswerMs,
        @Range(min = MIN, max = MAX, message = MESSAGE) Long finalJeopardyRevealMs,
        @Range(min = MIN, max = MAX, message = MESSAGE) Long roundIntroMs,
        @Range(min = MIN, max = MAX, message = MESSAGE) Long dailyDoubleRevealMs
) {
    private static final long MIN = GameSessionTimeouts.MIN_MILLIS;
    private static final long MAX = GameSessionTimeouts.MAX_MILLIS;
    // Explicit, so error responses read the same whatever the Accept-Language.
    private static final String MESSAGE = "must be between " + MIN + " and " + MAX;

    /** {@code current} with every duration this request sets replaced. */
    public GameSessionTimeouts applyTo(GameSessionTimeouts current) {
        return new GameSessionTimeouts(
                orElse(clueChoiceMs, current.clueChoice()),
                orElse(clueRevealMs, current.clueReveal()),
                orElse(dailyDoubleWagerMs, current.dailyDoubleWager()),
                orElse(buzzerMs, current.buzzer()),
                orElse(answerMs, current.answer()),
                orElse(answerRevealMs, current.answerReveal()),
                orElse(finalJeopardyWagerMs, current.finalJeopardyWager()),
                orElse(finalJeopardyAnswerMs, current.finalJeopardyAnswer()),
                orElse(finalJeopardyRevealMs, current.finalJeopardyReveal()),
                orElse(roundIntroMs, current.roundIntro()),
                orElse(dailyDoubleRevealMs, current.dailyDoubleReveal())
        );
    }

    private static Duration orElse(Long millis, Duration current) {
        return millis == null ? current : Duration.ofMillis(millis);
    }
}
