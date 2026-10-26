package com.nickneim.jeopardy.session.exception;

import java.util.Objects;

/**
 * An action the game rules don't allow right now. These are routine (every
 * buzz that loses a race is one), and only the code and message reach the
 * client, so no stack trace is recorded.
 */
public class InvalidGameActionException extends RuntimeException {

    private final GameErrorCode code;

    public InvalidGameActionException(
            GameErrorCode code,
            String message
    ) {
        super(message, null, false, false);
        this.code = Objects.requireNonNull(code, "code");
    }

    public GameErrorCode getCode() {
        return code;
    }
}
