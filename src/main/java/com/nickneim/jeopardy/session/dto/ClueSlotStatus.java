package com.nickneim.jeopardy.session.dto;

public enum ClueSlotStatus {
    /** There is a clue here that can still be chosen. */
    AVAILABLE,
    /** There is a clue here, but it has already been chosen. */
    PLAYED,
    /** There is no clue in this category on this row. */
    EMPTY
}
