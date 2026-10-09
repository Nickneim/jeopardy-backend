package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionState;

import java.time.Instant;
import java.util.UUID;

/**
 * One session in the lobby list. Like every other session response it is
 * public, so it must never contain tokens.
 */
public record GameSessionSummaryResponse(
        UUID id,
        Long gameId,
        String gameTitle,
        GameSessionState state,
        GameRound round,
        int playerCount,
        String hostName,
        Instant createdAt
) {

    public static GameSessionSummaryResponse from(GameSession session) {
        Long hostId = session.getHostPlayerId();
        String hostName = session.getActivePlayers()
                .stream()
                .filter(player -> player.getId().equals(hostId))
                .map(GameSessionPlayer::getName)
                .findFirst()
                .orElse(null);

        return new GameSessionSummaryResponse(
                session.getId(),
                session.getBoard().getGameId(),
                session.getBoard().getTitle(),
                session.getState(),
                session.getRound(),
                session.getActivePlayers().size(),
                hostName,
                session.getCreatedAt()
        );
    }
}
