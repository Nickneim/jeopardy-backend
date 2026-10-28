package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionState;
import com.nickneim.jeopardy.session.model.HostMode;
import com.nickneim.jeopardy.session.model.SessionCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

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
        int joinScore,
        // A custom show: gameId, gameSeason and gameDate are null, and
        // gameTitle is the show's name.
        boolean custom,
        // Clues on the board, in every round and Final Jeopardy.
        int clueCount,
        // TREBEK: the host runs the show (hostName is Trebek's, and
        // playerCount leaves them out). Null from an older instance.
        HostMode hostMode
) {

    /** A contestant session's summary. */
    public GameSessionSummaryResponse(
            SessionCode id,
            Long gameId,
            String gameTitle,
            String gameSeason,
            LocalDate gameDate,
            GameSessionState state,
            GameRound round,
            int playerCount,
            String hostName,
            Instant createdAt,
            boolean joinable,
            boolean allowLateJoin,
            int joinScore,
            boolean custom,
            int clueCount
    ) {
        this(id, gameId, gameTitle, gameSeason, gameDate, state, round, playerCount, hostName, createdAt,
                joinable, allowLateJoin, joinScore, custom, clueCount, HostMode.CONTESTANT);
    }

    public static GameSessionSummaryResponse from(GameSession session) {
        Long hostId = session.getHostPlayerId();
        List<GameSessionPlayer> activePlayers = session.getActivePlayers();
        GameSessionPlayer trebek = session.getTrebek();
        String hostName = Stream.concat(activePlayers.stream(), Stream.ofNullable(trebek))
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
                session.getJoinScore(),
                session.getBoard().isCustom(),
                session.getBoard().getClueCount(),
                session.getHostMode()
        );
    }
}
