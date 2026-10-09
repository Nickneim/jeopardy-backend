package com.nickneim.jeopardy.session.exception;

/**
 * A stored game that exists but can't be played in a session, such as one
 * imported without any clues. That's valid content, so this is a client
 * error, unlike the IllegalArgumentException a GameBoard throws for a broken
 * board.
 */
public class UnplayableGameException extends RuntimeException {

    public UnplayableGameException(Long gameId) {
        super("Game " + gameId + " has no clues and can't be played");
    }
}
