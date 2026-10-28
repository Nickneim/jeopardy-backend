package com.nickneim.jeopardy.session.model;

/**
 * Who ends a timed phase in a Trebek session. In a contestant session every
 * phase runs on its timer, whatever its mode.
 */
public enum TimerMode {
    /** The phase ends when its timer runs out (or Trebek ends it sooner). */
    AUTOMATIC,
    /** The phase has no timer and waits for Trebek. */
    TREBEK
}
