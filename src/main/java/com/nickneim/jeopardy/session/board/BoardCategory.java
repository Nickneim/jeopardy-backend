package com.nickneim.jeopardy.session.board;

import java.util.List;

public record BoardCategory(
        String name,
        List<BoardClue> clues
) {

    public BoardCategory {
        clues = List.copyOf(clues);
    }
}
