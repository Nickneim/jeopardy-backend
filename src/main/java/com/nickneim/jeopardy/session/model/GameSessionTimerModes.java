package com.nickneim.jeopardy.session.model;

import java.util.Objects;

/**
 * Which timed phases run on their timer and which wait for Trebek, one per
 * {@link GameSessionTimeouts} duration. Only used in a Trebek session; the
 * durations are kept whatever the mode.
 */
public record GameSessionTimerModes(
        TimerMode clueChoice,
        TimerMode clueReveal,
        TimerMode dailyDoubleWager,
        TimerMode buzzer,
        TimerMode answer,
        TimerMode answerReveal,
        TimerMode finalJeopardyWager,
        TimerMode finalJeopardyAnswer,
        TimerMode finalJeopardyReveal,
        TimerMode roundIntro,
        TimerMode dailyDoubleReveal
) {

    public GameSessionTimerModes {
        Objects.requireNonNull(clueChoice, "clueChoice");
        Objects.requireNonNull(clueReveal, "clueReveal");
        Objects.requireNonNull(dailyDoubleWager, "dailyDoubleWager");
        Objects.requireNonNull(buzzer, "buzzer");
        Objects.requireNonNull(answer, "answer");
        Objects.requireNonNull(answerReveal, "answerReveal");
        Objects.requireNonNull(finalJeopardyWager, "finalJeopardyWager");
        Objects.requireNonNull(finalJeopardyAnswer, "finalJeopardyAnswer");
        Objects.requireNonNull(finalJeopardyReveal, "finalJeopardyReveal");
        Objects.requireNonNull(roundIntro, "roundIntro");
        Objects.requireNonNull(dailyDoubleReveal, "dailyDoubleReveal");
    }

    /**
     * Trebek shows clues, wagers, answers and results; the players' own
     * phases and the title cards run on their timers.
     */
    public static GameSessionTimerModes defaults() {
        return new GameSessionTimerModes(
                TimerMode.AUTOMATIC,
                TimerMode.TREBEK,
                TimerMode.TREBEK,
                TimerMode.AUTOMATIC,
                TimerMode.AUTOMATIC,
                TimerMode.TREBEK,
                TimerMode.AUTOMATIC,
                TimerMode.AUTOMATIC,
                TimerMode.TREBEK,
                TimerMode.AUTOMATIC,
                TimerMode.AUTOMATIC
        );
    }

    /** The mode of the state's timer, or null for a state that has none. */
    public TimerMode of(GameSessionState state) {
        return switch (state) {
            case ROUND_INTRO -> roundIntro;
            case REVEALING_DAILY_DOUBLE -> dailyDoubleReveal;
            case CHOOSING_CLUE -> clueChoice;
            case REVEALING_CLUE -> clueReveal;
            case WAITING_FOR_DAILY_DOUBLE_WAGER -> dailyDoubleWager;
            case WAITING_FOR_BUZZER -> buzzer;
            case WAITING_FOR_ANSWER -> answer;
            case REVEALING_ANSWER -> answerReveal;
            case FINAL_JEOPARDY_WAGERING -> finalJeopardyWager;
            case FINAL_JEOPARDY_ANSWERING -> finalJeopardyAnswer;
            case FINAL_JEOPARDY_REVEALING -> finalJeopardyReveal;
            case WAITING_FOR_START, CLUE_PICKED, WAITING_FOR_RULING, FINISHED_GAME, CLOSED -> null;
        };
    }
}
