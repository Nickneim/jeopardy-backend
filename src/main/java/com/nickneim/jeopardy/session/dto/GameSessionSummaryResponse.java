package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionState;
import com.nickneim.jeopardy.session.model.SessionCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One session in the lobby list. Like every other session response it is
 * public, so it must never contain tokens.
 */
public record GameSessionSummaryResponse(
        SessionCode id,
        Long gameId,
        String gameTitle,
        // The game's season and air date, null when gameId is.
        String gameSeason,
        LocalDate gameDate,
        GameSessionState state,
        GameRound round,
        int playerCount,
        String hostName,
        Instant createdAt,
        // Whether a join would be accepted right now.
        boolean joinable,
        boolean allowLateJoin,
        int joinScore
) {

    public static GameSessionSummaryResponse from(GameSession session) {
        Long hostId = session.getHostPlayerId();
        List<GameSessionPlayer> activePlayers = session.getActivePlayers();
        String hostName = activePlayers
                .stream()
                .filter(player -> player.getId().equals(hostId))
                .map(GameSessionPlayer::getName)
                .findFirst()
                .orElse(null);

        return new GameSessionSummaryResponse(
                session.getId(),
                session.getBoard().getGameId(),
                session.getBoard().getTitle(),
                session.getBoard().getSeason(),
                session.getBoard().getGameDate(),
                session.getState(),
                session.getRound(),
                activePlayers.size(),
                hostName,
                session.getCreatedAt(),
                session.isJoinable(),
                session.isLateJoinAllowed(),
                session.getJoinScore()
        );
    }
}
