package com.nickneim.jeopardy.session.custom;

import com.nickneim.jeopardy.game.entity.GameRound;

/**
 * One thing that keeps a custom show from being played, and where it is:
 * the whole show (round null), a whole round or Final Jeopardy (column
 * null), a category (row null) or a clue. Columns and rows are the ones the
 * request gave. The message is English, ready to show to the host.
 */
public record CustomGameProblem(
        CustomGameProblemCode code,
        GameRound round,
        Integer column,
        Integer row,
        String message
) {
}
