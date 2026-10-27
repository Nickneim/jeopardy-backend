package com.nickneim.jeopardy.session.custom;

/** Why a custom show can't be played, one per {@link CustomGameProblem} (docs/api.md §3.2). */
public enum CustomGameProblemCode {
    NO_CLUES,
    SHOW_NAME_MISSING,
    SHOW_NAME_TOO_LONG,
    CATEGORY_NAME_MISSING,
    CATEGORY_NAME_TOO_LONG,
    FINAL_CLUE_MISSING,
    QUESTION_MISSING,
    QUESTION_TOO_LONG,
    ANSWER_MISSING,
    ANSWER_TOO_LONG,
    ANSWER_UNREADABLE,
    MEDIA_URL_MISSING,
    MEDIA_URL_INVALID,
    MEDIA_URL_NOT_ALLOWED,
    TOO_MANY_DAILY_DOUBLES
}
