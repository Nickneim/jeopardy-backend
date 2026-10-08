package com.nickneim.jeopardy.session.board;

import com.nickneim.jeopardy.game.entity.GameRound;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public record BoardRound(
        GameRound round,
        List<BoardCategory> categories
) {

    public BoardRound {
        Objects.requireNonNull(round, "round");
        categories = List.copyOf(categories);
    }

    public Stream<BoardClue> clues() {
        return categories.stream()
                .flatMap(category -> category.clues().stream());
    }
}
