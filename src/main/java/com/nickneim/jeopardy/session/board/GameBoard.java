package com.nickneim.jeopardy.session.board;

import com.nickneim.jeopardy.game.entity.Category;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.answer.AcceptedAnswers;
import com.nickneim.jeopardy.session.answer.AnswerCheck;
import com.nickneim.jeopardy.session.answer.AnswerMatcher;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The immutable board a live session is played from. It holds no Hibernate
 * entities, so it can be used from any thread for as long as the session lives.
 * Everything a session asks of it is worked out once, when it's built.
 *
 * <p>Lookups by clue id take any id, null included: a clue that isn't on
 * the board is an IllegalArgumentException, and isn't in any round's ids.
 */
public final class GameBoard {

    // The stored game this board was copied from, or null for a board that
    // isn't in the database (such as a custom game).
    private final Long gameId;
    // A show a host built in the browser (GameBoard.custom).
    private final boolean custom;
    private final String title;
    private final String season;
    private final LocalDate gameDate;
    private final List<BoardRound> rounds;
    private final Map<GameRound, BoardRound> roundsByType = new EnumMap<>(GameRound.class);
    private final Map<Long, BoardClue> cluesById = new HashMap<>();
    // The available clues are derived from these on every broadcast.
    private final Map<GameRound, Set<Long>> clueIdsByRound = new EnumMap<>(GameRound.class);
    private final Map<GameRound, Integer> highestValueByRound = new EnumMap<>(GameRound.class);
    // What players are told about each answer.
    private final Map<Long, AnswerHints> answerHints = new HashMap<>();
    private final BoardCategory finalJeopardyCategory;

    public GameBoard(List<BoardRound> rounds) {
        this(null, null, rounds);
    }

    public GameBoard(Long gameId, String title, List<BoardRound> rounds) {
        this(gameId, title, null, null, rounds);
    }

    /** @throws InvalidBoardException if the rounds can't be played */
    public GameBoard(Long gameId, String title, String season, LocalDate gameDate, List<BoardRound> rounds) {
        this(gameId, false, title, season, gameDate, rounds);
    }

    /**
     * A custom show's board, titled with the show's name. It has no stored
     * game, season or air date.
     *
     * @throws InvalidBoardException if the rounds can't be played
     */
    public static GameBoard custom(String name, List<BoardRound> rounds) {
        return new GameBoard(null, true, name, null, null, rounds);
    }

    private GameBoard(
            Long gameId,
            boolean custom,
            String title,
            String season,
            LocalDate gameDate,
            List<BoardRound> rounds
    ) {
        this.gameId = gameId;
        this.custom = custom;
        this.title = title;
        this.season = season;
        this.gameDate = gameDate;
        this.rounds = rounds.stream()
                .sorted(Comparator.comparing(BoardRound::round))
                .toList();

        for (BoardRound boardRound : this.rounds) {
            GameRound round = boardRound.round();

            if (roundsByType.put(round, boardRound) != null) {
                throw new InvalidBoardException("the board has two " + round + " rounds");
            }

            List<BoardClue> clues = boardRound.clues().toList();

            if (round == GameRound.FINAL_JEOPARDY && clues.size() > 1) {
                throw new InvalidBoardException("Final Jeopardy can only have one clue");
            }

            Set<Long> ids = new HashSet<>();

            for (BoardClue clue : clues) {
                if (cluesById.put(clue.id(), clue) != null) {
                    throw new InvalidBoardException("the board has two clues with id " + clue.id());
                }
                ids.add(clue.id());
                answerHints.put(clue.id(), answerHints(clue));
            }

            clueIdsByRound.put(round, Collections.unmodifiableSet(ids));
            clues.stream()
                    .mapToInt(BoardClue::value)
                    .max()
                    .ifPresent(highest -> highestValueByRound.put(round, highest));
        }

        if (cluesById.isEmpty()) {
            throw new InvalidBoardException("the board has no clues");
        }

        this.finalJeopardyCategory = getRound(GameRound.FINAL_JEOPARDY)
                .flatMap(finalRound -> finalRound.categories()
                        .stream()
                        .filter(category -> !category.clues().isEmpty())
                        .findFirst())
                .orElse(null);
    }

    // Each answer is parsed once, here. An answer that parses to no options
    // would mark every response wrong.
    private static AnswerHints answerHints(BoardClue clue) {
        AnswerMatcher matcher = AnswerMatcher.of(clue.answer());
        AcceptedAnswers accepted = matcher.acceptedAnswers();

        if (accepted.options().isEmpty()) {
            throw new InvalidBoardException(
                    "clue " + clue.id() + " has no answer a response could match: \"" + clue.answer() + "\""
            );
        }

        return new AnswerHints(matcher, matcher.expectsNumber(), accepted);
    }

    /**
     * Copies a database game into a board. This reads the game's lazy
     * collections, so call it inside a transaction (as
     * {@code GameBoardService.loadBoard} does). The board it returns holds no
     * entities and can be used after the transaction ends.
     *
     * @throws InvalidBoardException if the game can't be played, e.g. it has no clues
     */
    public static GameBoard from(Game game) {
        Set<Long> dailyDoubles = game.getDailyDoubles()
                .stream()
                .map(dailyDouble -> dailyDouble.getClue().getId())
                .collect(Collectors.toSet());

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
                            dailyDoubles.contains(clue.getId()),
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
                game.getId(),
                game.getTitle(),
                game.getSeason(),
                game.getGameDate(),
                categoriesByRound.entrySet()
                        .stream()
                        .map(entry -> new BoardRound(entry.getKey(), entry.getValue()))
                        .toList()
        );
    }

    /** The stored game's id, or null if the board isn't from the database. */
    public Long getGameId() {
        return gameId;
    }

    /** Whether this is a custom show's board ({@link #custom}). */
    public boolean isCustom() {
        return custom;
    }

    /** How many clues the board has, in every round, Final Jeopardy included. */
    public int getClueCount() {
        return cluesById.size();
    }

    /** The game's title (a custom show's name), or null if it has none. */
    public String getTitle() {
        return title;
    }

    /** The stored game's season, or null if the board isn't from the database. */
    public String getSeason() {
        return season;
    }

    /** The stored game's air date, or null if the board isn't from the database. */
    public LocalDate getGameDate() {
        return gameDate;
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

    /** The category the clue is in. */
    public BoardCategory getCategory(Long clueId) {
        BoardClue clue = getClue(clueId);

        return rounds.stream()
                .flatMap(round -> round.categories().stream())
                .filter(category -> category.clues().contains(clue))
                .findFirst()
                .orElseThrow();
    }

    public boolean isDailyDouble(Long clueId) {
        return getClue(clueId).dailyDouble();
    }

    /** Whether only an exact number is accepted for the clue ({@link AnswerMatcher#expectsNumber()}). */
    public boolean isNumberExpected(Long clueId) {
        return answerHints(clueId).numberExpected();
    }

    /** The clue's answer as players should see it ({@link AnswerMatcher#acceptedAnswers}). */
    public AcceptedAnswers getAcceptedAnswers(Long clueId) {
        return answerHints(clueId).acceptedAnswers();
    }

    /** Checks a response against the clue's answer ({@link AnswerMatcher#check(String, boolean)}). */
    public AnswerCheck checkAnswer(Long clueId, String response, boolean questionRequired) {
        return answerHints(clueId).matcher().check(response, questionRequired);
    }

    private AnswerHints answerHints(Long clueId) {
        return answerHints.get(getClue(clueId).id());
    }

    private record AnswerHints(AnswerMatcher matcher, boolean numberExpected, AcceptedAnswers acceptedAnswers) {}

    public Set<Long> getClueIds(GameRound round) {
        return clueIdsByRound.getOrDefault(round, Set.of());
    }

    /** The highest clue value in the round, or 0 if it has no clues. */
    public int getHighestValue(GameRound round) {
        return highestValueByRound.getOrDefault(round, 0);
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

    /** The Final Jeopardy category, or null if the board has none. */
    public BoardCategory getFinalJeopardyCategory() {
        return finalJeopardyCategory;
    }

    /** The Final Jeopardy clue, or null if the board has none. */
    public BoardClue getFinalJeopardyClue() {
        return finalJeopardyCategory == null ? null : finalJeopardyCategory.clues().getFirst();
    }

    private boolean hasClues(GameRound round) {
        return !getClueIds(round).isEmpty();
    }
}
