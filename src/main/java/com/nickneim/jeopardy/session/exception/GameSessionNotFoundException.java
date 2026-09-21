package com.nickneim.jeopardy.session.exception;

import java.util.UUID;

public class GameSessionNotFoundException extends RuntimeException {

    public GameSessionNotFoundException(UUID id) {
        super("Game session not found: " + id);
    }
}
