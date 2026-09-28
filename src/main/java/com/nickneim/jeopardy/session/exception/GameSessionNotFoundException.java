package com.nickneim.jeopardy.session.exception;

import java.util.UUID;

public class GameSessionNotFoundException extends RuntimeException {

    public GameErrorCode getCode() {
        return GameErrorCode.SESSION_NOT_FOUND;
    }

    public GameSessionNotFoundException(UUID id) {
        super("Game session not found: " + id);
    }
}
