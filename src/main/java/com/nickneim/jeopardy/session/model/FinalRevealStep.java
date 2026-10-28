package com.nickneim.jeopardy.session.model;

/**
 * How far one player's Final Jeopardy reveal has got in a Trebek session,
 * where players are revealed one at a time. In this order.
 */
public enum FinalRevealStep {
    HIDDEN,
    /** Their wager is shown. */
    WAGER,
    /** Their answer is shown too. */
    ANSWER,
    /** Trebek's ruling was applied to their score. */
    AWARDED
}
