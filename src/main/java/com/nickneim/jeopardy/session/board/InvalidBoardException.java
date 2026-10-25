package com.nickneim.jeopardy.session.board;

/**
 * Thrown while building a board ({@link GameBoard}, {@link BoardRound},
 * {@link BoardCategory}, {@link BoardClue}) from content that can't be
 * played. {@code GameBoardService.loadBoard} turns it into an
 * {@code UnplayableGameException} for a stored game.
 */
public class InvalidBoardException extends IllegalArgumentException {

    public InvalidBoardException(String message) {
        super(message);
    }
}
