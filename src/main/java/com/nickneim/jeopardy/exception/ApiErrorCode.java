package com.nickneim.jeopardy.exception;

/**
 * The code of a REST error that isn't a game action's. A game action error
 * that reaches REST keeps its GameErrorCode instead (ApiErrorResponse.code
 * holds either), so the names never mean two different things.
 */
public enum ApiErrorCode {
    INVALID_REQUEST,
    GAME_NOT_FOUND,
    CLUE_NOT_FOUND,
    UNPLAYABLE_GAME,
    SESSION_ALREADY_EXISTS,
    SESSION_NOT_JOINABLE
}
