package com.nickneim.jeopardy.session;

import java.util.UUID;

public class GameSessionNotFoundException extends RuntimeException {

    public GameSessionNotFoundException(UUID id) {
        super("Game session not found: " + id);
    }
}
