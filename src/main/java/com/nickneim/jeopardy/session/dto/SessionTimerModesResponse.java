package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.model.GameSessionTimerModes;
import com.nickneim.jeopardy.session.model.TimerMode;

/** Which phases wait for Trebek, keyed like SessionTimersResponse. */
public record SessionTimerModesResponse(
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

    public static SessionTimerModesResponse from(GameSessionTimerModes modes) {
        return new SessionTimerModesResponse(
                modes.clueChoice(),
                modes.clueReveal(),
                modes.dailyDoubleWager(),
                modes.buzzer(),
                modes.answer(),
                modes.answerReveal(),
                modes.finalJeopardyWager(),
                modes.finalJeopardyAnswer(),
                modes.finalJeopardyReveal(),
                modes.roundIntro(),
                modes.dailyDoubleReveal()
        );
    }
}
