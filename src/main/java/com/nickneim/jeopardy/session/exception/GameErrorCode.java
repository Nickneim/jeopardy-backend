package com.nickneim.jeopardy.session.exception;

public enum GameErrorCode {
    SESSION_NOT_FOUND,
    NOT_YOUR_TURN,
    INVALID_STATE,
    // A moment too late: someone else buzzed first, or the timer ran out.
    TOO_LATE,
    CLUE_NOT_FOUND,
    CLUE_ALREADY_PLAYED,
    PLAYER_NOT_IN_SESSION,
    INVALID_WAGER,
    INVALID_REQUEST,
    UNAUTHENTICATED,
    NOT_HOST,
    PLAYER_NOT_FOUND,
    INTERNAL_ERROR
}
