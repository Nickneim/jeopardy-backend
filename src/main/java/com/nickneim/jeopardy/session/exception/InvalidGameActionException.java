package com.nickneim.jeopardy.session.exception;

public class InvalidGameActionException extends RuntimeException {

    private final GameErrorCode code;

    public InvalidGameActionException(
            GameErrorCode code,
            String message
    ) {
        super(message);
        this.code = code;
    }

    public GameErrorCode getCode() {
        return code;
    }
}