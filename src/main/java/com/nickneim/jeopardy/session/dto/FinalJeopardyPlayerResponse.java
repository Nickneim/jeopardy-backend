package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.model.ClueAnswer;
import com.nickneim.jeopardy.session.model.FinalJeopardy;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;

import java.util.List;

/**
 * One player's Final Jeopardy status. {@code maxWager} is the most they can
 * wager, set only while wagering (it follows from their public score).
 * Everyone receives every player's entry, so {@code wager}, {@code answer}, {@code correct},
 * {@code phrasedAsQuestion}, {@code matchedAnswers} and {@code alsoAccepted}
 * stay null until the reveal; before that only whether they've submitted is
 * shown. {@code phrasedAsQuestion} is also null for a player who didn't
 * answer, and the last two are only set for a correct answer.
 */
public record FinalJeopardyPlayerResponse(
        Long playerId,
        boolean hasWagered,
        boolean hasAnswered,
        Integer maxWager,
        Integer wager,
        String answer,
        Boolean correct,
        Boolean phrasedAsQuestion,
        List<String> matchedAnswers,
        List<String> alsoAccepted
) {

    /**
     * Only whether the player has wagered and answered, before the reveal,
     * and while {@code wagering} how much they can wager.
     */
    static FinalJeopardyPlayerResponse submitted(GameSessionPlayer player, FinalJeopardy finalJeopardy, boolean wagering) {
        Long playerId = player.getId();

        return new FinalJeopardyPlayerResponse(
                playerId,
                finalJeopardy.hasWager(playerId),
                finalJeopardy.hasAnswer(playerId),
                wagering ? FinalJeopardy.maximumWager(player) : null,
                null, null, null, null, null, null
        );
    }

    /** Everything, from the reveal on. No answer counts as a wrong one. */
    static FinalJeopardyPlayerResponse revealed(GameSessionPlayer player, FinalJeopardy finalJeopardy) {
        Long playerId = player.getId();
        ClueAnswer answer = finalJeopardy.answerOf(playerId);

        return new FinalJeopardyPlayerResponse(
                playerId,
                finalJeopardy.hasWager(playerId),
                finalJeopardy.hasAnswer(playerId),
                null,
                finalJeopardy.getWager(playerId),
                answer.answer(),
                answer.correct(),
                answer.phrasedAsQuestion(),
                answer.matchedAnswers(),
                answer.alsoAccepted()
        );
    }
}
