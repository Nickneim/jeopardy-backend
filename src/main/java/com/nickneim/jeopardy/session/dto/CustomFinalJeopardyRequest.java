package com.nickneim.jeopardy.session.dto;

/**
 * A custom show's Final Jeopardy: a category and its one clue, with no media
 * and no Daily Double. It's all or nothing: every field blank means no
 * Final Jeopardy, like a null one; otherwise the name, question and answer
 * are all needed ({@code CustomGameBoards}).
 */
public record CustomFinalJeopardyRequest(
        String name,
        String comments,
        String question,
        String answer
) {
}
