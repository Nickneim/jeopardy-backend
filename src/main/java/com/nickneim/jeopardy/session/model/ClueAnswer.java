package com.nickneim.jeopardy.session.model;

/**
 * One player's answer to an ordinary clue or Daily Double. {@code answer} is
 * the text they submitted, or null if they ran out of time.
 */
public record ClueAnswer(
        Long playerId,
        String answer,
        boolean correct
) {}
