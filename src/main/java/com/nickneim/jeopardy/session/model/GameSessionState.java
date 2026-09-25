package com.nickneim.jeopardy.session.model;

public enum GameSessionState {
    WAITING_FOR_START,
    CHOOSING_CLUE,
    REVEALING_CLUE,
    WAITING_FOR_BUZZER,
    WAITING_FOR_ANSWER,
    REVEALING_ANSWER,
    FINISHED_GAME
}
