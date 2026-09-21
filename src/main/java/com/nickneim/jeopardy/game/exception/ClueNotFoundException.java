package com.nickneim.jeopardy.game.exception;

public class ClueNotFoundException extends RuntimeException {

    public ClueNotFoundException(Long id) {
        super("Clue not found: " + id);
    }
}