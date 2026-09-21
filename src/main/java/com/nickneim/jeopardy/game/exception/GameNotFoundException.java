package com.nickneim.jeopardy.game.exception;

public class GameNotFoundException extends RuntimeException {

    public GameNotFoundException(Long id) {
        super("Game not found: " + id);
    }
}