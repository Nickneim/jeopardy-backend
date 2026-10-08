package com.nickneim.jeopardy.session.exception;

public enum GameErrorCode {
    SESSION_NOT_FOUND,
    NOT_YOUR_TURN,
    INVALID_STATE,
    CLUE_NOT_FOUND,
    CLUE_ALREADY_PLAYED,
    PLAYER_NOT_IN_SESSION,
    INVALID_WAGER,
    INVALID_REQUEST
}