package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionState;
import com.nickneim.jeopardy.session.model.SessionCloseReason;
import com.nickneim.jeopardy.session.model.SessionCode;

import java.time.Duration;
import java.util.List;
import java.util.Set;

public record GameSessionResponse(
        SessionCode id,
        long version,
        GameRound round,
        GameSessionState state,
        SessionCloseReason closeReason,
        Long phaseDurationMs,
        Long phaseRemainingMs,
        SessionTimersResponse timers,
        boolean allowLateJoin,
        int joinScore,
        List<PlayerResponse> players,
        Long hostPlayerId,
        Long playerChoosingClue,
        Long playerAnswering,
        Set<Long> playersLockedOut,
        List<BoardCategoryResponse> board,
        CurrentClueResponse currentClue,
        List<ClueAnswerResponse> clueAnswers,
        FinalJeopardyResponse finalJeopardy
) {
    public static GameSessionResponse from(GameSession session) {
        return from(session, System.nanoTime());
    }

    /**
     * Builds the response as of {@code nowNanos} (in System.nanoTime() terms),
     * which is when {@code phaseRemainingMs} is measured. Timing is sent as
     * durations rather than a deadline, so a client's clock doesn't matter.
     */
    public static GameSessionResponse from(GameSession session, long nowNanos) {
        Duration phaseDuration = session.getPhaseDuration();
        Duration phaseRemaining = session.getPhaseRemaining(nowNanos);

        return new GameSessionResponse(
                session.getId(),
                session.getVersion(),
                session.getRound(),
                session.getState(),
                session.getCloseReason(),
                phaseDuration == null ? null : phaseDuration.toMillis(),
                phaseRemaining == null ? null : phaseRemaining.toMillis(),
                SessionTimersResponse.from(session.getTimeouts()),
                session.isLateJoinAllowed(),
                session.getJoinScore(),
                session.getPlayers().stream()
                        .map(PlayerResponse::from)
                        .toList(),
                session.getHostPlayerId(),
                session.getPlayerChoosingClue(),
                session.getPlayerAnswering(),
                session.getPlayersLockedOut(),
                session.getBoard()
                        .getRound(session.getRound())
                        .map(round -> BoardCategoryResponse.fromRound(round, session.getPlayedClues()))
                        .orElse(List.of()),
                CurrentClueResponse.from(session),
                session.getClueAnswers().stream()
                        .map(ClueAnswerResponse::from)
                        .toList(),
                FinalJeopardyResponse.from(session)
        );
    }
}
