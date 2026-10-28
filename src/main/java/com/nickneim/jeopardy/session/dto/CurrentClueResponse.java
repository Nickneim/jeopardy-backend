package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.game.entity.ClueType;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.model.GameSession;

/**
 * The clue being played, as every player sees it. What is revealed depends
 * on the state: a Daily Double's question, clue type and media URL stay
 * hidden until the wager is placed, and the answer stays hidden until the
 * answer is revealed. A clue Trebek hasn't shown yet (CLUE_PICKED) only
 * says which one it is: even {@code dailyDouble} is null, so the board
 * doesn't give a Daily Double away before Trebek shows it.
 * {@code wager} is only set for a Daily Double once the wager is placed;
 * {@code minWager} and {@code maxWager}, the chooser's allowed range, only
 * while it is being placed.
 * {@code numberExpected} (only an exact number is accepted) is shown with the
 * question, since it says something about the answer. {@code acceptedAnswers}
 * is the answer as players should see it, revealed with {@code answer}.
 */
public record CurrentClueResponse(
        Long clueId,
        int value,
        Boolean dailyDouble,
        Integer wager,
        Integer minWager,
        Integer maxWager,
        String question,
        ClueType clueType,
        String mediaUrl,
        Boolean numberExpected,
        String answer,
        AcceptedAnswersResponse acceptedAnswers
) {

    /** Returns null when no clue is being played. */
    public static CurrentClueResponse from(GameSession session) {
        boolean showDailyDouble = true;
        boolean showQuestion;
        boolean showAnswer;

        switch (session.getState()) {
            case CLUE_PICKED -> {
                showDailyDouble = false;
                showQuestion = false;
                showAnswer = false;
            }
            case WAITING_FOR_DAILY_DOUBLE_WAGER -> {
                showQuestion = false;
                showAnswer = false;
            }
            case REVEALING_CLUE, WAITING_FOR_BUZZER, WAITING_FOR_ANSWER, WAITING_FOR_RULING -> {
                showQuestion = true;
                showAnswer = false;
            }
            case REVEALING_ANSWER -> {
                showQuestion = true;
                showAnswer = true;
            }
            default -> {
                return null;
            }
        }

        GameBoard board = session.getBoard();
        BoardClue clue = board.getClue(session.getChosenClueId());

        return new CurrentClueResponse(
                clue.id(),
                clue.value(),
                showDailyDouble ? clue.dailyDouble() : null,
                session.getDailyDoubleWager(),
                session.getMinimumDailyDoubleWager(),
                session.getMaximumDailyDoubleWager(),
                showQuestion ? clue.question() : null,
                showQuestion ? clue.clueType() : null,
                showQuestion ? clue.mediaUrl() : null,
                showQuestion ? board.isNumberExpected(clue.id()) : null,
                showAnswer ? clue.answer() : null,
                showAnswer ? AcceptedAnswersResponse.from(board.getAcceptedAnswers(clue.id())) : null
        );
    }
}
