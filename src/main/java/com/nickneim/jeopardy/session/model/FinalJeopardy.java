package com.nickneim.jeopardy.session.model;

import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Final Jeopardy submissions of a game, keyed by player id. They stay
 * secret until FINAL_JEOPARDY_REVEALING; see FinalJeopardyResponse.
 * GameSession decides the phases and who may submit; this holds what was
 * submitted, Trebek's rulings in a Trebek session, and how it scores.
 */
public class FinalJeopardy {

    private final Map<Long, Integer> wagers = new HashMap<>();
    private final Map<Long, ClueAnswer> answers = new HashMap<>();
    // Trebek's rulings, each the player's answer as ruled (see ClueAnswer).
    private final Map<Long, ClueAnswer> rulings = new HashMap<>();

    /**
     * Wagers go from 0 up to the player's score, so a player with a score of
     * 0 or less can only wager 0 but still plays. A player may change their
     * wager until the wagering phase ends.
     */
    void placeWager(GameSessionPlayer player, int wager) {
        int maximumWager = maximumWager(player);
        if (wager < 0 || wager > maximumWager) {
            throw new InvalidGameActionException(
                    GameErrorCode.INVALID_WAGER,
                    "Your wager must be between 0 and " + maximumWager + "!"
            );
        }

        wagers.put(player.getId(), wager);
    }

    /**
     * Lowers every wager above its player's maximum to it, when wagering
     * closes. Only Trebek's score changes can push a score below a wager
     * placed earlier.
     */
    void capWagers(List<GameSessionPlayer> players) {
        for (GameSessionPlayer player : players) {
            wagers.computeIfPresent(player.getId(), (id, wager) -> Math.min(wager, maximumWager(player)));
        }
    }

    /** The most a player can wager: their score, or 0 if it isn't positive. */
    public static int maximumWager(GameSessionPlayer player) {
        return Math.max(0, player.getScore());
    }

    /** A player may change their answer until the answering phase ends. */
    void answer(ClueAnswer answer) {
        answers.put(answer.playerId(), answer);
    }

    /** Trebek's ruling on a player, as their answer ruled; it replaces any earlier one. */
    void rule(ClueAnswer ruled) {
        rulings.put(ruled.playerId(), ruled);
    }

    void remove(Long playerId) {
        wagers.remove(playerId);
        answers.remove(playerId);
        rulings.remove(playerId);
    }

    public boolean hasWager(Long playerId) {
        return wagers.containsKey(playerId);
    }

    public boolean hasAnswer(Long playerId) {
        return answers.containsKey(playerId);
    }

    /** The player's wager; a player who didn't wager wagers 0. */
    public int getWager(Long playerId) {
        return wagers.getOrDefault(playerId, 0);
    }

    /** Whether the player's result is correct (see {@link #resultOf}); no answer counts as wrong. */
    public boolean isCorrect(Long playerId) {
        return resultOf(playerId).correct();
    }

    /**
     * The player's answer, checked when they gave it, or a timed-out one
     * (no text, no check) if they didn't answer.
     */
    public ClueAnswer answerOf(Long playerId) {
        ClueAnswer answer = answers.get(playerId);

        return answer == null ? ClueAnswer.timedOut(playerId) : answer;
    }

    /** Trebek's ruling on the player, or null if there is none. */
    public Boolean rulingOf(Long playerId) {
        ClueAnswer ruled = rulings.get(playerId);

        return ruled == null ? null : ruled.ruling();
    }

    /** What decides the player's result: Trebek's ruling if there is one, otherwise their checked answer. */
    public ClueAnswer resultOf(Long playerId) {
        ClueAnswer ruled = rulings.get(playerId);

        return ruled == null ? answerOf(playerId) : ruled;
    }
}
