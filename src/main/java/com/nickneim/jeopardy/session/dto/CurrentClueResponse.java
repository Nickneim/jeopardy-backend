package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.game.entity.ClueType;
import com.nickneim.jeopardy.session.answer.AnswerMatcher;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.model.GameSession;

/**
 * The clue being played, as every player sees it. What is revealed depends
 * on the state: a Daily Double's question, clue type and media URL stay
 * hidden until the wager is placed, and the answer stays hidden until the
 * answer is revealed.
 * {@code wager} is only set for a Daily Double once the wager is placed.
 * {@code numberExpected} (only an exact number is accepted) is shown with the
 * question, since it says something about the answer. {@code acceptedAnswers}
 * is the answer as players should see it, revealed with {@code answer}.
 */
public record CurrentClueResponse(
        Long clueId,
        int value,
        boolean dailyDouble,
        Integer wager,
        String question,
        ClueType clueType,
        String mediaUrl,
        Boolean numberExpected,
        String answer,
        AcceptedAnswersResponse acceptedAnswers
) {

    /** Returns null when no clue is being played. */
    public static CurrentClueResponse from(GameSession session) {
        boolean showQuestion;
        boolean showAnswer;

        switch (session.getState()) {
            case WAITING_FOR_DAILY_DOUBLE_WAGER -> {
                showQuestion = false;
                showAnswer = false;
            }
            case REVEALING_CLUE, WAITING_FOR_BUZZER, WAITING_FOR_ANSWER -> {
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

        BoardClue clue = session.getBoard().getClue(session.getChosenClueId());

        return new CurrentClueResponse(
                clue.id(),
                clue.value(),
                clue.dailyDouble(),
                session.getDailyDoubleWager(),
                showQuestion ? clue.question() : null,
                showQuestion ? clue.clueType() : null,
                showQuestion ? clue.mediaUrl() : null,
                showQuestion ? AnswerMatcher.expectsNumber(clue.answer()) : null,
                showAnswer ? clue.answer() : null,
                showAnswer ? AcceptedAnswersResponse.from(AnswerMatcher.acceptedAnswers(clue.answer())) : null
        );
    }
}
