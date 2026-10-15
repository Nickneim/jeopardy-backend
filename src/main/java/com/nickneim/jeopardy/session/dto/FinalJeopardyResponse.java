package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.answer.AnswerMatcher;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.model.GameSession;

import java.util.List;

/**
 * Final Jeopardy as every player sees it. The category and its comments are
 * shown while wagering, the clue (and whether only an exact number is
 * accepted) once answering starts, and the correct response plus
 * everyone's wagers and answers only from the reveal on.
 */
public record FinalJeopardyResponse(
        String category,
        String categoryComments,
        String question,
        Boolean numberExpected,
        String answer,
        List<FinalJeopardyPlayerResponse> players
) {

    /** Returns null when Final Jeopardy isn't being (or hasn't been) played. */
    public static FinalJeopardyResponse from(GameSession session) {
        boolean showQuestion;
        boolean showResults;

        switch (session.getState()) {
            case FINAL_JEOPARDY_WAGERING -> {
                showQuestion = false;
                showResults = false;
            }
            case FINAL_JEOPARDY_ANSWERING -> {
                showQuestion = true;
                showResults = false;
            }
            case FINAL_JEOPARDY_REVEALING -> {
                showQuestion = true;
                showResults = true;
            }
            case FINISHED_GAME -> {
                // A game without Final Jeopardy finishes in an earlier round.
                if (session.getRound() != GameRound.FINAL_JEOPARDY) {
                    return null;
                }
                showQuestion = true;
                showResults = true;
            }
            default -> {
                return null;
            }
        }

        BoardClue clue = session.getFinalJeopardyClue();

        List<FinalJeopardyPlayerResponse> players = session.getPlayers()
                .stream()
                .map(player -> new FinalJeopardyPlayerResponse(
                        player.getId(),
                        session.hasFinalJeopardyWager(player.getId()),
                        session.hasFinalJeopardyAnswer(player.getId()),
                        showResults ? session.getFinalJeopardyWager(player.getId()) : null,
                        showResults ? session.getFinalJeopardyAnswer(player.getId()) : null,
                        showResults ? session.isFinalJeopardyAnswerCorrect(player.getId()) : null,
                        showResults ? session.isFinalJeopardyAnswerPhrasedAsQuestion(player.getId()) : null
                ))
                .toList();

        BoardCategory category = session.getFinalJeopardyCategory();

        return new FinalJeopardyResponse(
                category.name(),
                category.comments(),
                showQuestion ? clue.question() : null,
                showQuestion ? AnswerMatcher.expectsNumber(clue.answer()) : null,
                showResults ? clue.answer() : null,
                players
        );
    }
}
