package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.game.entity.ClueType;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.model.GameSession;

/** The whole clue in play, correct response included, for Trebek only. */
public record TrebekClueResponse(
        Long clueId,
        int value,
        boolean dailyDouble,
        String categoryName,
        String question,
        ClueType clueType,
        String mediaUrl,
        boolean numberExpected,
        String answer,
        AcceptedAnswersResponse acceptedAnswers
) {

    /** From the moment a clue is picked until it ends; otherwise null. */
    static TrebekClueResponse from(GameSession session) {
        switch (session.getState()) {
            case CLUE_PICKED, WAITING_FOR_DAILY_DOUBLE_WAGER, REVEALING_CLUE, WAITING_FOR_BUZZER,
                 WAITING_FOR_ANSWER, WAITING_FOR_RULING, REVEALING_ANSWER -> {
            }
            default -> {
                return null;
            }
        }

        GameBoard board = session.getBoard();
        BoardClue clue = board.getClue(session.getChosenClueId());

        return new TrebekClueResponse(
                clue.id(),
                clue.value(),
                clue.dailyDouble(),
                board.getCategory(clue.id()).name(),
                clue.question(),
                clue.clueType(),
                clue.mediaUrl(),
                board.isNumberExpected(clue.id()),
                clue.answer(),
                AcceptedAnswersResponse.from(board.getAcceptedAnswers(clue.id()))
        );
    }
}
