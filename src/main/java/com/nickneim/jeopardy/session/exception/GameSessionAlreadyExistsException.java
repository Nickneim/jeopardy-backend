package com.nickneim.jeopardy.session.exception;

import com.nickneim.jeopardy.session.model.SessionCode;

/**
 * Someone tried to create a session with a code that's already in use.
 * Clients choose the code at random, so this means another session got it
 * first (or the same create was sent twice): the client picks another code
 * and tries again. Creating is a REST call, so this maps to an HTTP error
 * (409) rather than a GameErrorCode.
 */
public class GameSessionAlreadyExistsException extends RuntimeException {

    public GameSessionAlreadyExistsException(SessionCode id) {
        super("Game session already exists: " + id);
    }
}
