package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public record BoardCategoryResponse(
        String name,
        String comments,
        List<ClueSlotResponse> slots
) {

    /**
     * Builds the grid for one round. Every category gets one slot per row,
     * up to the highest row used anywhere in the round, so the grid is
     * rectangular even when some categories have missing clues.
     */
    public static List<BoardCategoryResponse> fromRound(
            BoardRound round,
            Set<Long> playedClues
    ) {
        int rowCount = round.clues()
                .mapToInt(BoardClue::row)
                .max()
                .orElse(0);

        return round.categories()
                .stream()
                .map(category -> from(category, rowCount, playedClues))
                .toList();
    }

    private static BoardCategoryResponse from(
            BoardCategory category,
            int rowCount,
            Set<Long> playedClues
    ) {
        Map<Integer, BoardClue> cluesByRow = category.clues()
                .stream()
                .collect(Collectors.toMap(BoardClue::row, Function.identity()));

        List<ClueSlotResponse> slots = new ArrayList<>();

        for (int row = 1; row <= rowCount; row++) {
            BoardClue clue = cluesByRow.get(row);

            if (clue == null) {
                slots.add(ClueSlotResponse.empty(row));
                continue;
            }

            slots.add(new ClueSlotResponse(
                    row,
                    clue.id(),
                    clue.value(),
                    playedClues.contains(clue.id())
                            ? ClueSlotStatus.PLAYED
                            : ClueSlotStatus.AVAILABLE
            ));
        }

        return new BoardCategoryResponse(category.name(), category.comments(), List.copyOf(slots));
    }
}
