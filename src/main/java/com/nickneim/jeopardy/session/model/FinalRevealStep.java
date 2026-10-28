package com.nickneim.jeopardy.session.model;

/**
 * How far one player's Final Jeopardy reveal has got. Players are revealed
 * one at a time, each through these steps in this order.
 */
public enum FinalRevealStep {
    HIDDEN,
    /** Their wager is shown. */
    WAGER,
    /** Their answer is shown too. */
    ANSWER,
    /** Their result (Trebek's ruling in a Trebek session) was applied to their score. */
    AWARDED
}
