package com.nickneim.jeopardy.session.exception;

import com.nickneim.jeopardy.session.model.SessionCode;

/**
 * The session doesn't exist (or no longer does). Over STOMP it's reported
 * like any other invalid action, as SESSION_NOT_FOUND; over REST,
 * ApiExceptionHandler maps it to 404.
 */
public class GameSessionNotFoundException extends InvalidGameActionException {

    public GameSessionNotFoundException(SessionCode id) {
        super(GameErrorCode.SESSION_NOT_FOUND, "Game session not found: " + id);
    }
}
