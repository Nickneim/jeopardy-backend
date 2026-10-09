package com.nickneim.jeopardy.session.board;

import com.nickneim.jeopardy.game.entity.Category;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.entity.GameRound;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The immutable board a live session is played from. It holds no Hibernate
 * entities, so it can be used from any thread for as long as the session lives.
 */
public final class GameBoard {

    private final List<BoardRound> rounds;
    private final Map<GameRound, BoardRound> roundsByType;
    private final Map<Long, BoardClue> cluesById;

    public GameBoard(List<BoardRound> rounds) {
        this.rounds = rounds.stream()
                .sorted(Comparator.comparing(BoardRound::round))
                .toList();

        Map<GameRound, BoardRound> byType = new EnumMap<>(GameRound.class);
        Map<Long, BoardClue> byId = new HashMap<>();

        for (BoardRound boardRound : this.rounds) {
            if (byType.put(boardRound.round(), boardRound) != null) {
                throw new IllegalArgumentException("Duplicate round: " + boardRound.round());
            }

            boardRound.clues().forEach(clue -> {
                if (byId.put(clue.id(), clue) != null) {
                    throw new IllegalArgumentException("Duplicate clue id: " + clue.id());
                }
            });

            if (boardRound.round() == GameRound.FINAL_JEOPARDY && boardRound.clues().count() > 1) {
                throw new IllegalArgumentException("Final Jeopardy can only have one clue");
            }
        }

        if (byId.isEmpty()) {
            throw new IllegalArgumentException("A game board needs at least one clue");
        }

        this.roundsByType = byType;
        this.cluesById = Map.copyOf(byId);
    }

    /**
     * Copies a database game into a board. This reads the game's lazy
     * collections, so call it inside a transaction (as
     * {@code GameBoardService.loadBoard} does). The board it returns holds no
     * entities and can be used after the transaction ends.
     */
    public static GameBoard from(Game game) {
        Map<GameRound, List<BoardCategory>> categoriesByRound =
                new EnumMap<>(GameRound.class);

        for (Category category : game.getCategories()) {
            List<BoardClue> clues = category.getClues()
                    .stream()
                    .map(clue -> new BoardClue(
                            clue.getId(),
                            clue.getRowNumber(),
                            clue.getQuestion(),
                            clue.getAnswer(),
                            clue.getValue(),
                            game.isDailyDouble(clue.getId()),
                            clue.getClueType(),
                            clue.getMediaUrl()
                    ))
                    .toList();

            categoriesByRound
                    .computeIfAbsent(
                            GameRound.fromColumn(category.getColumnNumber()),
                            round -> new ArrayList<>()
                    )
                    .add(new BoardCategory(category.getName(), category.getComments(), clues));
        }

        return new GameBoard(
                categoriesByRound.entrySet()
                        .stream()
                        .map(entry -> new BoardRound(entry.getKey(), entry.getValue()))
                        .toList()
        );
    }

    public List<BoardRound> getRounds() {
        return rounds;
    }

    public Optional<BoardRound> getRound(GameRound round) {
        return Optional.ofNullable(roundsByType.get(round));
    }

    public BoardClue getClue(Long clueId) {
        BoardClue clue = cluesById.get(clueId);

        if (clue == null) {
            throw new IllegalArgumentException("Clue not on this board: " + clueId);
        }

        return clue;
    }

    public boolean isDailyDouble(Long clueId) {
        BoardClue clue = cluesById.get(clueId);

        return clue != null && clue.dailyDouble();
    }

    public Set<Long> getClueIds(GameRound round) {
        BoardRound boardRound = roundsByType.get(round);

        if (boardRound == null) {
            return Set.of();
        }

        return boardRound.clues()
                .map(BoardClue::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    public int getHighestValue(GameRound round) {
        BoardRound boardRound = roundsByType.get(round);

        if (boardRound == null) {
            return 0;
        }

        return boardRound.clues()
                .mapToInt(BoardClue::value)
                .max()
                .orElse(0);
    }

    /** The first round that has any clues. */
    public GameRound getFirstRound() {
        return rounds.stream()
                .map(BoardRound::round)
                .filter(this::hasClues)
                .findFirst()
                .orElseThrow();
    }

    /** The next round after {@code current} that has any clues, if there is one. */
    public Optional<GameRound> getNextRound(GameRound current) {
        return rounds.stream()
                .map(BoardRound::round)
                .filter(round -> round.compareTo(current) > 0)
                .filter(this::hasClues)
                .findFirst();
    }

    private boolean hasClues(GameRound round) {
        return !getClueIds(round).isEmpty();
    }
}
