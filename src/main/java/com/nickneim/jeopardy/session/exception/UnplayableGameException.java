package com.nickneim.jeopardy.session.exception;

import com.nickneim.jeopardy.session.board.InvalidBoardException;

/**
 * A stored game that exists but can't be played in a session, such as one
 * imported without any clues. That's valid content, so this is a client
 * error. The reason is the {@link InvalidBoardException} the board threw.
 */
public class UnplayableGameException extends RuntimeException {

    public UnplayableGameException(Long gameId, InvalidBoardException reason) {
        super("Game " + gameId + " can't be played: " + reason.getMessage(), reason);
    }
}
