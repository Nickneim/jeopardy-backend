package com.nickneim.jeopardy.exception;

public class GameNotFoundException extends RuntimeException {

    public GameNotFoundException(Long id) {
        super("Game not found: " + id);
    }
}