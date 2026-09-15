package com.nickneim.jeopardy.exception;

public class ClueNotFoundException extends RuntimeException {

    public ClueNotFoundException(Long id) {
        super("Clue not found: " + id);
    }
}