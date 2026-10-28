package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.model.GameSessionTimerModes;
import com.nickneim.jeopardy.session.model.TimerMode;

/**
 * Which phases wait for Trebek, keyed like SessionTimersRequest, for the
 * host's update-settings action. Every field is optional: one that is left
 * out (or null) keeps its current mode.
 */
public record SessionTimerModesRequest(
        TimerMode clueChoiceMs,
        TimerMode clueRevealMs,
        TimerMode dailyDoubleWagerMs,
        TimerMode buzzerMs,
        TimerMode answerMs,
        TimerMode answerRevealMs,
        TimerMode finalJeopardyWagerMs,
        TimerMode finalJeopardyAnswerMs,
        TimerMode finalJeopardyRevealMs,
        TimerMode roundIntroMs,
        TimerMode dailyDoubleRevealMs
) {

    /** {@code current} with every mode this request sets replaced. */
    public GameSessionTimerModes applyTo(GameSessionTimerModes current) {
        return new GameSessionTimerModes(
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

    private static TimerMode orElse(TimerMode mode, TimerMode current) {
        return mode == null ? current : mode;
    }
}
