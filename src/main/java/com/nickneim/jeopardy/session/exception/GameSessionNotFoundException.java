package com.nickneim.jeopardy.session.exception;

import com.nickneim.jeopardy.session.model.SessionCode;

public class GameSessionNotFoundException extends RuntimeException {

    public GameErrorCode getCode() {
        return GameErrorCode.SESSION_NOT_FOUND;
    }

    public GameSessionNotFoundException(SessionCode id) {
        super("Game session not found: " + id);
    }
}
