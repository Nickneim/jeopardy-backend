package com.nickneim.jeopardy.session.model;

import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Final Jeopardy submissions of a game, keyed by player id. They stay
 * secret until FINAL_JEOPARDY_REVEALING; see FinalJeopardyResponse.
 * GameSession decides the phases and who may submit; this holds what was
 * submitted and how it scores.
 */
public class FinalJeopardy {

    private final Map<Long, Integer> wagers = new HashMap<>();
    private final Map<Long, ClueAnswer> answers = new HashMap<>();

    /**
     * Wagers go from 0 up to the player's score, so a player with a score of
     * 0 or less can only wager 0 but still plays. A player may change their
     * wager until the wagering phase ends.
     */
    void placeWager(GameSessionPlayer player, int wager) {
        int maximumWager = Math.max(0, player.getScore());
        if (wager < 0 || wager > maximumWager) {
            throw new InvalidGameActionException(GameErrorCode.INVALID_WAGER, "You can't wager that amount!");
        }

        wagers.put(player.getId(), wager);
    }

    /** Players who didn't wager in time wager 0. */
    void wagerZeroForMissing(List<GameSessionPlayer> players) {
        for (GameSessionPlayer player : players) {
            wagers.putIfAbsent(player.getId(), 0);
        }
    }

    /** A player may change their answer until the answering phase ends. */
    void answer(ClueAnswer answer) {
        answers.put(answer.playerId(), answer);
    }

    /** Each player wins their wager with a correct answer and loses it otherwise (no answer counts as wrong). */
    Map<GameSessionPlayer, Integer> scoreChanges(List<GameSessionPlayer> players) {
        Map<GameSessionPlayer, Integer> changes = new LinkedHashMap<>();

        for (GameSessionPlayer player : players) {
            int wager = getWager(player.getId());

            changes.put(player, isCorrect(player.getId()) ? wager : -wager);
        }

        return changes;
    }

    void remove(Long playerId) {
        wagers.remove(playerId);
        answers.remove(playerId);
    }

    public boolean hasWager(Long playerId) {
        return wagers.containsKey(playerId);
    }

    public boolean hasAnswer(Long playerId) {
        return answers.containsKey(playerId);
    }

    public int getWager(Long playerId) {
        return wagers.getOrDefault(playerId, 0);
    }

    public boolean isCorrect(Long playerId) {
        return answerOf(playerId).correct();
    }

    /**
     * The player's answer, checked when they gave it, or a timed-out one
     * (no text, no check) if they didn't answer.
     */
    public ClueAnswer answerOf(Long playerId) {
        ClueAnswer answer = answers.get(playerId);

        return answer == null ? ClueAnswer.timedOut(playerId) : answer;
    }
}
