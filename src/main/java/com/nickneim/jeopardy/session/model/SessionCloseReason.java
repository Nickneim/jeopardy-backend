package com.nickneim.jeopardy.session.model;

/** Why a session was closed and removed from memory. */
public enum SessionCloseReason {
    /** Every player left (or is waiting to be removed after leaving). */
    ABANDONED,
    /** Nothing happened in the lobby for too long, so the game never started. */
    IDLE,
    /** The game finished a while ago. */
    FINISHED,
    /** Trebek left during the game, which ends it. */
    TREBEK_LEFT
}
