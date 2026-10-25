package com.nickneim.jeopardy.session.board;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A category of a live session's board. {@code comments} is the host's note
 * about the category, shown with its name, or null if it has none.
 */
public record BoardCategory(
        String name,
        String comments,
        List<BoardClue> clues
) {

    public BoardCategory {
        Set<Integer> rows = new HashSet<>();

        for (BoardClue clue : clues) {
            if (!rows.add(clue.row())) {
                throw new InvalidBoardException(
                        "category " + name + " has two clues on row " + clue.row()
                );
            }
        }

        clues = clues.stream()
                .sorted(Comparator.comparingInt(BoardClue::row))
                .toList();
    }

    /** A category without comments. */
    public BoardCategory(String name, List<BoardClue> clues) {
        this(name, null, clues);
    }
}
