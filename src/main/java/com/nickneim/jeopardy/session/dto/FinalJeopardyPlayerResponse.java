package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.model.ClueAnswer;
import com.nickneim.jeopardy.session.model.FinalJeopardy;
import com.nickneim.jeopardy.session.model.FinalRevealStep;
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
 *
 * <p>In a Trebek session the reveal goes one player at a time, and
 * {@code revealStep} says how far this player's has got: the wager, then
 * the answer, then the result (Trebek's ruling). It's null otherwise.
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
        List<String> alsoAccepted,
        FinalRevealStep revealStep
) {

    /** An entry of a contestant session, which has no reveal steps. */
    public FinalJeopardyPlayerResponse(
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
        this(playerId, hasWagered, hasAnswered, maxWager, wager, answer, correct,
                phrasedAsQuestion, matchedAnswers, alsoAccepted, null);
    }

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
        return revealedUpTo(player, finalJeopardy, null);
    }

    /**
     * A Trebek session's reveal, as far as the player's has got: from
     * {@link FinalRevealStep#WAGER} their wager, from {@code ANSWER} their
     * answer, and once {@code AWARDED} whether Trebek ruled it correct.
     */
    static FinalJeopardyPlayerResponse revealedUpTo(GameSessionPlayer player, FinalJeopardy finalJeopardy, FinalRevealStep step) {
        Long playerId = player.getId();
        boolean showWager = step == null || step.compareTo(FinalRevealStep.WAGER) >= 0;
        boolean showAnswer = step == null || step.compareTo(FinalRevealStep.ANSWER) >= 0;
        boolean showResult = step == null || step == FinalRevealStep.AWARDED;
        // What decides the result: Trebek's ruling, if any, or the check.
        ClueAnswer answer = finalJeopardy.resultOf(playerId);

        return new FinalJeopardyPlayerResponse(
                playerId,
                finalJeopardy.hasWager(playerId),
                finalJeopardy.hasAnswer(playerId),
                null,
                showWager ? finalJeopardy.getWager(playerId) : null,
                showAnswer ? answer.answer() : null,
                showResult ? answer.correct() : null,
                showAnswer ? answer.phrasedAsQuestion() : null,
                showResult ? answer.matchedAnswers() : null,
                showResult ? answer.alsoAccepted() : null,
                step
        );
    }
}
