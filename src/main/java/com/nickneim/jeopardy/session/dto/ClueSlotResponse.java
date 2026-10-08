package com.nickneim.jeopardy.session.dto;

/**
 * One cell of the board as clients see it. It deliberately has no question,
 * answer or Daily Double flag: every player receives the board, so those
 * must never be part of it. {@code clueId} and {@code value} are null for
 * an {@link ClueSlotStatus#EMPTY} slot.
 */
public record ClueSlotResponse(
        int row,
        Long clueId,
        Integer value,
        ClueSlotStatus status
) {

    public static ClueSlotResponse empty(int row) {
        return new ClueSlotResponse(row, null, null, ClueSlotStatus.EMPTY);
    }
}
