package com.nickneim.jeopardy.session.exception;

import java.util.UUID;

/**
 * Someone tried to create a session with an id that's already in use.
 * Clients choose the id (a fresh random UUID), so this only happens when the
 * same create is sent twice. Creating is a REST call, so this maps to an
 * HTTP error (409) rather than a GameErrorCode.
 */
public class GameSessionAlreadyExistsException extends RuntimeException {

    public GameSessionAlreadyExistsException(UUID id) {
        super("Game session already exists: " + id);
    }
}
