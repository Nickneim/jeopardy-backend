package com.nickneim.jeopardy.session.exception;

/**
 * Someone tried to join a session that doesn't take new players right now
 * (see GameSession.isJoinable): the host blocked joining after the start,
 * Final Jeopardy is past its wagering, or the game has finished. Joining is
 * a REST call, so this maps to an HTTP error (409) rather than a
 * GameErrorCode.
 */
public class LateJoinNotAllowedException extends RuntimeException {

    public LateJoinNotAllowedException(String message) {
        super(message);
    }
}
