package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.model.FinalJeopardy;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;

import java.util.List;

/**
 * Final Jeopardy as every player sees it. The category and its comments are
 * shown while wagering, the clue (and whether only an exact number is
 * accepted) once answering starts, and the correct response (as stored and
 * as {@code acceptedAnswers} to show) plus everyone's wagers and answers only
 * from the reveal on.
 */
public record FinalJeopardyResponse(
        String category,
        String categoryComments,
        String question,
        Boolean numberExpected,
        String answer,
        AcceptedAnswersResponse acceptedAnswers,
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

        BoardClue clue = session.getBoard().getFinalJeopardyClue();
        FinalJeopardy finalJeopardy = session.getFinalJeopardy();

        List<FinalJeopardyPlayerResponse> players = session.getPlayers()
                .stream()
                .map(GameSessionPlayer::getId)
                .map(playerId -> showResults
                        ? FinalJeopardyPlayerResponse.revealed(playerId, finalJeopardy)
                        : FinalJeopardyPlayerResponse.submitted(playerId, finalJeopardy))
                .toList();

        BoardCategory category = session.getBoard().getFinalJeopardyCategory();

        return new FinalJeopardyResponse(
                category.name(),
                category.comments(),
                showQuestion ? clue.question() : null,
                showQuestion ? session.getBoard().isNumberExpected(clue.id()) : null,
                showResults ? clue.answer() : null,
                showResults ? AcceptedAnswersResponse.from(session.getBoard().getAcceptedAnswers(clue.id())) : null,
                players
        );
    }
}
