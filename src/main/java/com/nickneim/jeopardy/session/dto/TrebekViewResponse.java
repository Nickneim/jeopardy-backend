package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.model.GameSession;

/**
 * What only Trebek may see, sent to Trebek's own connections
 * (/user/queue/trebek), never to the session's topic: the whole clue in
 * play with its answer, the answer waiting for a ruling, and the Final
 * Jeopardy wagers and answers before they're revealed. {@code version} is
 * the version of the broadcast it goes with.
 */
public record TrebekViewResponse(
        long version,
        TrebekClueResponse clue,
        TrebekPendingAnswerResponse pendingAnswer,
        TrebekFinalJeopardyResponse finalJeopardy
) {

    public static TrebekViewResponse from(GameSession session) {
        return new TrebekViewResponse(
                session.getVersion(),
                TrebekClueResponse.from(session),
                TrebekPendingAnswerResponse.from(session.getPendingAnswer()),
                TrebekFinalJeopardyResponse.from(session)
        );
    }
}
