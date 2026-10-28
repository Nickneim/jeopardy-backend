package com.nickneim.jeopardy.session.model;

public enum GameSessionState {
    WAITING_FOR_START,
    /**
     * A title card for the round about to start (the session's round), before
     * each round, Final Jeopardy included.
     */
    ROUND_INTRO,
    CHOOSING_CLUE,
    /** Trebek sessions only: a clue was chosen, and Trebek hasn't shown it yet. */
    CLUE_PICKED,
    /** The chosen clue is a Daily Double, announced before the chooser wagers. */
    REVEALING_DAILY_DOUBLE,
    WAITING_FOR_DAILY_DOUBLE_WAGER,
    REVEALING_CLUE,
    WAITING_FOR_BUZZER,
    WAITING_FOR_ANSWER,
    /** Trebek sessions only: the answer is in (or typing closed), and Trebek hasn't ruled on it. */
    WAITING_FOR_RULING,
    REVEALING_ANSWER,
    /** Final Jeopardy: the category is shown and every player places a wager. */
    FINAL_JEOPARDY_WAGERING,
    /** Final Jeopardy: the clue is shown and every player submits an answer. */
    FINAL_JEOPARDY_ANSWERING,
    /** Final Jeopardy: answers, wagers and the correct response are shown. */
    FINAL_JEOPARDY_REVEALING,
    FINISHED_GAME,
    /**
     * The session was removed from memory. Only its last broadcast has this
     * state; see {@link SessionCloseReason}.
     */
    CLOSED
}
