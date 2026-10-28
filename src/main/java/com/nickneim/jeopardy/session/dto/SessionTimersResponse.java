package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.model.GameSessionTimeouts;

/** The session's phase durations in milliseconds, as configured. */
public record SessionTimersResponse(
        long clueChoiceMs,
        long clueRevealMs,
        long dailyDoubleWagerMs,
        long buzzerMs,
        long answerMs,
        long answerRevealMs,
        long finalJeopardyWagerMs,
        long finalJeopardyAnswerMs,
        long finalJeopardyRevealMs,
        long roundIntroMs,
        long dailyDoubleRevealMs
) {

    public static SessionTimersResponse from(GameSessionTimeouts timeouts) {
        return new SessionTimersResponse(
                timeouts.clueChoice().toMillis(),
                timeouts.clueReveal().toMillis(),
                timeouts.dailyDoubleWager().toMillis(),
                timeouts.buzzer().toMillis(),
                timeouts.answer().toMillis(),
                timeouts.answerReveal().toMillis(),
                timeouts.finalJeopardyWager().toMillis(),
                timeouts.finalJeopardyAnswer().toMillis(),
                timeouts.finalJeopardyReveal().toMillis(),
                timeouts.roundIntro().toMillis(),
                timeouts.dailyDoubleReveal().toMillis()
        );
    }
}
