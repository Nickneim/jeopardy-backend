package com.nickneim.jeopardy.session.model;

public enum GameSessionState {
    WAITING_FOR_START,
    CHOOSING_CLUE,
    WAITING_FOR_DAILY_DOUBLE_WAGER,
    REVEALING_CLUE,
    WAITING_FOR_BUZZER,
    WAITING_FOR_ANSWER,
    REVEALING_ANSWER,
    /** Final Jeopardy: the category is shown and every player places a wager. */
    FINAL_JEOPARDY_WAGERING,
    /** Final Jeopardy: the clue is shown and every player submits an answer. */
    FINAL_JEOPARDY_ANSWERING,
    /** Final Jeopardy: answers, wagers and the correct response are shown. */
    FINAL_JEOPARDY_REVEALING,
    FINISHED_GAME
}
