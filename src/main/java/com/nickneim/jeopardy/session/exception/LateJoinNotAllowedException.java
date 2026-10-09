package com.nickneim.jeopardy.session.exception;

/**
 * Someone tried to join a session that has started and whose host blocked
 * joining after the start. Joining is a REST call, so this maps to an HTTP
 * error (409) rather than a GameErrorCode.
 */
public class LateJoinNotAllowedException extends RuntimeException {

    public LateJoinNotAllowedException() {
        super("This game has already started and doesn't accept new players");
    }
}
