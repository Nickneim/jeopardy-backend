package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.model.ClueAnswer;
import com.nickneim.jeopardy.session.model.FinalJeopardy;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionState;

import java.util.List;

/**
 * Final Jeopardy for Trebek: the clue and correct response, and every
 * player's wager and answer as they come in, with the check's suggestion
 * and Trebek's ruling.
 */
public record TrebekFinalJeopardyResponse(
        String question,
        String answer,
        AcceptedAnswersResponse acceptedAnswers,
        boolean numberExpected,
        List<Player> players
) {

    /**
     * {@code wager} is null while wagering until they wager (afterwards a
     * missing one is 0). {@code suggestedCorrect} is the check's result,
     * null until they answer or answering closes (no answer suggests wrong).
     * {@code ruling} is null until Trebek rules.
     */
    public record Player(
            Long playerId,
            Integer wager,
            String answer,
            Boolean suggestedCorrect,
            Boolean phrasedAsQuestion,
            List<String> matchedAnswers,
            Boolean ruling
    ) {}

    /** From Final Jeopardy's wagering until the game ends; otherwise null. */
    static TrebekFinalJeopardyResponse from(GameSession session) {
        GameSessionState state = session.getState();
        boolean wagering = state == GameSessionState.FINAL_JEOPARDY_WAGERING;

        if (!wagering && state != GameSessionState.FINAL_JEOPARDY_ANSWERING
                && state != GameSessionState.FINAL_JEOPARDY_REVEALING) {
            return null;
        }

        GameBoard board = session.getBoard();
        BoardClue clue = board.getFinalJeopardyClue();
        FinalJeopardy finalJeopardy = session.getFinalJeopardy();
        boolean answeringClosed = state == GameSessionState.FINAL_JEOPARDY_REVEALING;

        return new TrebekFinalJeopardyResponse(
                clue.question(),
                clue.answer(),
                AcceptedAnswersResponse.from(board.getAcceptedAnswers(clue.id())),
                board.isNumberExpected(clue.id()),
                session.getPlayers()
                        .stream()
                        .map(player -> player(player, finalJeopardy, wagering, answeringClosed))
                        .toList()
        );
    }

    private static Player player(GameSessionPlayer player, FinalJeopardy finalJeopardy, boolean wagering, boolean answeringClosed) {
        Long playerId = player.getId();
        boolean answered = finalJeopardy.hasAnswer(playerId);
        ClueAnswer answer = finalJeopardy.answerOf(playerId);

        return new Player(
                playerId,
                !wagering || finalJeopardy.hasWager(playerId) ? finalJeopardy.getWager(playerId) : null,
                answer.answer(),
                answered || answeringClosed ? answer.correct() : null,
                answer.phrasedAsQuestion(),
                answer.matchedAnswers(),
                finalJeopardy.rulingOf(playerId)
        );
    }
}
