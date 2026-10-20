package com.nickneim.jeopardy.session.lobby;

import com.nickneim.jeopardy.game.dto.PageResponse;
import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import com.nickneim.jeopardy.session.model.GameSessionState;
import com.nickneim.jeopardy.session.service.GameSessionService;
import com.nickneim.jeopardy.session.model.SessionCode;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The lobby list: the sessions of every instance, this one's read directly
 * and the others' asked for (PeerSessions). With a single instance that's
 * just this one's.
 */
@Service
@EnableConfigurationProperties(SessionLobbyProperties.class)
public class SessionLobby {

    private final GameSessionService gameSessionService;
    private final PeerSessions peerSessions;

    public SessionLobby(GameSessionService gameSessionService, PeerSessions peerSessions) {
        this.gameSessionService = gameSessionService;
        this.peerSessions = peerSessions;
    }

    /**
     * The open sessions for a lobby page, newest first (ties by id), without
     * finished ones unless {@code includeFinished}.
     */
    public PageResponse<GameSessionSummaryResponse> listSessions(
            boolean includeFinished,
            int page,
            int size
    ) {
        // A session is only ever on one instance, but if this instance's
        // address isn't configured, it answers for itself too: keep the
        // summary read directly.
        Map<SessionCode, GameSessionSummaryResponse> byId = new LinkedHashMap<>();
        Stream.concat(gameSessionService.localSummaries().stream(), peerSessions.fetch().stream())
                .forEach(summary -> byId.putIfAbsent(summary.id(), summary));

        List<GameSessionSummaryResponse> matching = byId.values()
                .stream()
                .filter(summary -> includeFinished || summary.state() != GameSessionState.FINISHED_GAME)
                .sorted(Comparator.comparing(GameSessionSummaryResponse::createdAt)
                        .reversed()
                        .thenComparing(GameSessionSummaryResponse::id))
                .toList();

        int from = (int) Math.min((long) page * size, matching.size());
        int to = Math.min(from + size, matching.size());

        return new PageResponse<>(
                matching.subList(from, to),
                page,
                size,
                matching.size(),
                (matching.size() + size - 1) / size
        );
    }
}
