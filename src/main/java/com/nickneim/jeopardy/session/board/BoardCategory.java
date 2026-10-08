package com.nickneim.jeopardy.session.board;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record BoardCategory(
        String name,
        List<BoardClue> clues
) {

    public BoardCategory {
        Set<Integer> rows = new HashSet<>();

        for (BoardClue clue : clues) {
            if (!rows.add(clue.row())) {
                throw new IllegalArgumentException(
                        "Category " + name + " has two clues on row " + clue.row()
                );
            }
        }

        clues = clues.stream()
                .sorted(Comparator.comparingInt(BoardClue::row))
                .toList();
    }
}
