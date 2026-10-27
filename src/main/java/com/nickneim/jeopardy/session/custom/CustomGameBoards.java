package com.nickneim.jeopardy.session.custom;

import com.nickneim.jeopardy.exception.InvalidParameterException;
import com.nickneim.jeopardy.game.entity.ClueType;
import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.answer.AnswerFormat;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.dto.CustomCategoryRequest;
import com.nickneim.jeopardy.session.dto.CustomClueRequest;
import com.nickneim.jeopardy.session.dto.CustomFinalJeopardyRequest;
import com.nickneim.jeopardy.session.dto.CustomGameRequest;
import com.nickneim.jeopardy.session.exception.InvalidCustomGameException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static com.nickneim.jeopardy.dto.QueryParameters.trimToNull;
import static com.nickneim.jeopardy.session.custom.CustomGameProblemCode.*;

/**
 * Turns a custom show into a {@link GameBoard}, once it's known to be
 * playable (docs/api.md §3.2, "What makes a custom show playable"). Every
 * problem is collected before anything is built, so the host learns about
 * all of them at once. Lengths are in code points, as for player names.
 *
 * <p>Clue ids are made up here, 1, 2, 3... in board order (round, column,
 * row, then Final Jeopardy), since custom clues aren't in the database.
 * Questions are kept exactly as written, tags included.
 */
public final class CustomGameBoards {

    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_QUESTION_LENGTH = 1000;
    public static final int MAX_ANSWER_LENGTH = 500;
    public static final int MAX_MEDIA_URL_LENGTH = 2000;

    private static final List<GameRound> CLUE_ROUNDS = List.of(GameRound.JEOPARDY, GameRound.DOUBLE_JEOPARDY);

    private CustomGameBoards() {}

    /**
     * @throws InvalidParameterException if two categories share a column, or
     *         two clues a row (a client bug, so a plain 400)
     * @throws InvalidCustomGameException with every problem, if the show
     *         can't be played
     */
    public static GameBoard toBoard(CustomGameRequest request) {
        requireUniquePositions(request);

        List<CustomGameProblem> problems = problems(request);
        if (!problems.isEmpty()) {
            throw new InvalidCustomGameException(problems);
        }

        return build(request);
    }

    private static void requireUniquePositions(CustomGameRequest request) {
        for (GameRound round : CLUE_ROUNDS) {
            String field = "customGame." + fieldName(round);
            Set<Integer> columns = new HashSet<>();
            List<CustomCategoryRequest> categories = categories(request, round);

            for (int i = 0; i < categories.size(); i++) {
                CustomCategoryRequest category = categories.get(i);

                if (!columns.add(category.column())) {
                    throw new InvalidParameterException(
                            field + " has two categories in column " + category.column()
                    );
                }

                Set<Integer> rows = new HashSet<>();
                for (CustomClueRequest clue : category.clues()) {
                    if (!rows.add(clue.row())) {
                        throw new InvalidParameterException(
                                field + "[" + i + "].clues has two clues on row " + clue.row()
                        );
                    }
                }
            }
        }
    }

    // ---------- the rules ----------

    private static List<CustomGameProblem> problems(CustomGameRequest request) {
        List<CustomGameProblem> problems = new ArrayList<>();
        CustomFinalJeopardyRequest finalJeopardy = request.finalJeopardy();

        long clueCount = CLUE_ROUNDS.stream()
                .flatMap(round -> categories(request, round).stream())
                .mapToLong(category -> category.clues().size())
                .sum();
        if (clueCount == 0 && !hasFinalClue(finalJeopardy)) {
            problems.add(new CustomGameProblem(NO_CLUES, null, null, null, "The show has no clues"));
        }

        String name = trimToNull(request.name());
        if (name == null) {
            problems.add(new CustomGameProblem(SHOW_NAME_MISSING, null, null, null, "The show has no name"));
        } else if (length(name) > MAX_NAME_LENGTH) {
            problems.add(new CustomGameProblem(
                    SHOW_NAME_TOO_LONG, null, null, null,
                    "The show's name " + tooLong(name, MAX_NAME_LENGTH)
            ));
        }

        for (GameRound round : CLUE_ROUNDS) {
            roundProblems(round, categories(request, round), problems);
        }

        if (finalJeopardy != null) {
            finalJeopardyProblems(finalJeopardy, problems);
        }

        return problems;
    }

    private static void roundProblems(
            GameRound round,
            List<CustomCategoryRequest> categories,
            List<CustomGameProblem> problems
    ) {
        long dailyDoubles = categories.stream()
                .flatMap(category -> category.clues().stream())
                .filter(CustomClueRequest::dailyDouble)
                .count();
        int maxDailyDoubles = maxDailyDoubles(round);

        if (dailyDoubles > maxDailyDoubles) {
            problems.add(new CustomGameProblem(
                    TOO_MANY_DAILY_DOUBLES, round, null, null,
                    roundName(round) + " has " + dailyDoubles + " Daily Doubles; it can have at most " + maxDailyDoubles
            ));
        }

        for (CustomCategoryRequest category : byColumn(categories)) {
            int column = category.column();
            String name = trimToNull(category.name());

            if (name == null) {
                if (!category.clues().isEmpty()) {
                    problems.add(new CustomGameProblem(
                            CATEGORY_NAME_MISSING, round, column, null,
                            "Category " + column + " has clues but no name"
                    ));
                }
            } else if (length(name) > MAX_NAME_LENGTH) {
                problems.add(new CustomGameProblem(
                        CATEGORY_NAME_TOO_LONG, round, column, null,
                        "Category " + column + "'s name " + tooLong(name, MAX_NAME_LENGTH)
                ));
            }

            for (CustomClueRequest clue : byRow(category.clues())) {
                Location at = new Location(round, column, clue.row(), "This clue");
                textProblems(at, clue.question(), clue.answer(), problems);
                mediaProblems(at, clue.clueType(), clue.mediaUrl(), problems);
            }
        }
    }

    // All or nothing: a Final Jeopardy with nothing in it is no Final Jeopardy.
    private static void finalJeopardyProblems(CustomFinalJeopardyRequest finalJeopardy, List<CustomGameProblem> problems) {
        String name = trimToNull(finalJeopardy.name());

        if (name == null && !hasFinalClue(finalJeopardy)) {
            return;
        }

        GameRound round = GameRound.FINAL_JEOPARDY;

        if (name == null) {
            problems.add(new CustomGameProblem(
                    CATEGORY_NAME_MISSING, round, null, null,
                    "Final Jeopardy has a clue but no category"
            ));
        } else if (length(name) > MAX_NAME_LENGTH) {
            problems.add(new CustomGameProblem(
                    CATEGORY_NAME_TOO_LONG, round, null, null,
                    "The Final Jeopardy category's name " + tooLong(name, MAX_NAME_LENGTH)
            ));
        }

        if (!hasFinalClue(finalJeopardy)) {
            problems.add(new CustomGameProblem(
                    FINAL_CLUE_MISSING, round, null, null,
                    "Final Jeopardy has a category but no clue"
            ));
            return;
        }

        textProblems(
                new Location(round, null, null, "The Final Jeopardy clue"),
                finalJeopardy.question(),
                finalJeopardy.answer(),
                problems
        );
    }

    // Rules 5 and 6: a question and an answer within their lengths, and an
    // answer that follows the format and that some response could match.
    private static void textProblems(Location at, String question, String answer, List<CustomGameProblem> problems) {
        if (isBlank(question)) {
            problems.add(at.problem(QUESTION_MISSING, at.subject() + " has no text"));
        } else if (length(question) > MAX_QUESTION_LENGTH) {
            problems.add(at.problem(QUESTION_TOO_LONG, at.subject() + "'s text " + tooLong(question, MAX_QUESTION_LENGTH)));
        }

        if (isBlank(answer)) {
            problems.add(at.problem(ANSWER_MISSING, at.subject() + " has no correct response"));
        } else if (length(answer) > MAX_ANSWER_LENGTH) {
            problems.add(at.problem(ANSWER_TOO_LONG, "The correct response " + tooLong(answer, MAX_ANSWER_LENGTH)));
        } else {
            Optional<String> unreadable = AnswerFormat.problem(answer);
            unreadable.ifPresent(reason -> problems.add(
                    at.problem(ANSWER_UNREADABLE, "The correct response can't be read: " + reason)
            ));
        }
    }

    // Rule 7: a link, on any host, exactly when the clue isn't text.
    private static void mediaProblems(Location at, ClueType type, String mediaUrl, List<CustomGameProblem> problems) {
        String kind = "This " + type.name().toLowerCase(Locale.ROOT) + " clue";

        if (type == ClueType.TEXT) {
            if (!isBlank(mediaUrl)) {
                problems.add(at.problem(
                        MEDIA_URL_NOT_ALLOWED,
                        kind + " has a media link; only image, sound and video clues can have one"
                ));
            }
        } else if (isBlank(mediaUrl)) {
            problems.add(at.problem(MEDIA_URL_MISSING, kind + " has no media link"));
        } else if (length(mediaUrl) > MAX_MEDIA_URL_LENGTH) {
            problems.add(at.problem(MEDIA_URL_INVALID, kind + "'s media link " + tooLong(mediaUrl, MAX_MEDIA_URL_LENGTH)));
        } else if (!isHttpUrl(mediaUrl)) {
            problems.add(at.problem(MEDIA_URL_INVALID, kind + "'s media link isn't an http or https URL"));
        }
    }

    static boolean isHttpUrl(String text) {
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme();

            return scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null
                    && !uri.getHost().isEmpty();
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    /** Where a problem is, and what its messages call the clue there. */
    private record Location(GameRound round, Integer column, Integer row, String subject) {

        CustomGameProblem problem(CustomGameProblemCode code, String message) {
            return new CustomGameProblem(code, round, column, row, message);
        }
    }

    // ---------- building ----------

    private static GameBoard build(CustomGameRequest request) {
        List<BoardRound> rounds = new ArrayList<>();
        long nextId = 1;

        for (GameRound round : CLUE_ROUNDS) {
            List<BoardCategory> categories = new ArrayList<>();

            for (CustomCategoryRequest category : byColumn(categories(request, round))) {
                String name = trimToNull(category.name());

                // An unnamed column without clues is just unused.
                if (name == null && category.clues().isEmpty()) {
                    continue;
                }

                List<BoardClue> clues = new ArrayList<>();
                for (CustomClueRequest clue : byRow(category.clues())) {
                    clues.add(new BoardClue(
                            nextId++,
                            clue.row(),
                            clue.question(),
                            clue.answer(),
                            value(round, clue.row()),
                            clue.dailyDouble(),
                            clue.clueType(),
                            clue.clueType() == ClueType.TEXT ? null : clue.mediaUrl()
                    ));
                }

                categories.add(new BoardCategory(name, trimToNull(category.comments()), clues));
            }

            if (!categories.isEmpty()) {
                rounds.add(new BoardRound(round, categories));
            }
        }

        CustomFinalJeopardyRequest finalJeopardy = request.finalJeopardy();
        if (finalJeopardy != null && hasFinalClue(finalJeopardy)) {
            BoardClue clue = new BoardClue(
                    nextId,
                    1,
                    finalJeopardy.question(),
                    finalJeopardy.answer(),
                    value(GameRound.FINAL_JEOPARDY, 1),
                    false
            );
            rounds.add(new BoardRound(GameRound.FINAL_JEOPARDY, List.of(new BoardCategory(
                    trimToNull(finalJeopardy.name()),
                    trimToNull(finalJeopardy.comments()),
                    List.of(clue)
            ))));
        }

        return GameBoard.custom(request.name().strip(), rounds);
    }

    // As for stored games (Clue.getValue).
    private static int value(GameRound round, int row) {
        return switch (round) {
            case JEOPARDY -> 200 * row;
            case DOUBLE_JEOPARDY -> 400 * row;
            case FINAL_JEOPARDY -> 2000;
        };
    }

    // ---------- helpers ----------

    // Rule 8. Fewer are fine: a show has exactly the Daily Doubles pinned.
    private static int maxDailyDoubles(GameRound round) {
        return round == GameRound.JEOPARDY ? 1 : 2;
    }

    private static boolean hasFinalClue(CustomFinalJeopardyRequest finalJeopardy) {
        return finalJeopardy != null && !(isBlank(finalJeopardy.question()) && isBlank(finalJeopardy.answer()));
    }

    private static List<CustomCategoryRequest> categories(CustomGameRequest request, GameRound round) {
        return round == GameRound.JEOPARDY ? request.jeopardyRound() : request.doubleJeopardyRound();
    }

    private static List<CustomCategoryRequest> byColumn(List<CustomCategoryRequest> categories) {
        return categories.stream().sorted(Comparator.comparing(CustomCategoryRequest::column)).toList();
    }

    private static List<CustomClueRequest> byRow(List<CustomClueRequest> clues) {
        return clues.stream().sorted(Comparator.comparing(CustomClueRequest::row)).toList();
    }

    private static String fieldName(GameRound round) {
        return round == GameRound.JEOPARDY ? "jeopardyRound" : "doubleJeopardyRound";
    }

    private static String roundName(GameRound round) {
        return round == GameRound.JEOPARDY ? "Jeopardy" : "Double Jeopardy";
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    // Code points, what JavaScript's [...text].length counts.
    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private static String tooLong(String text, int max) {
        return "is " + length(text) + " characters long; the most is " + max;
    }
}
