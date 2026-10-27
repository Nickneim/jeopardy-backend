package com.nickneim.jeopardy.session.lobby;

import com.nickneim.jeopardy.dto.PageResponse;
import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import com.nickneim.jeopardy.session.service.GameSessionService;
import com.nickneim.jeopardy.session.model.SessionCode;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

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
     * The open sessions matching {@code filter} for a lobby page, in
     * {@code sort} order. Filtering and sorting run on every instance's
     * sessions together, before paging, so the totals count only matches.
     */
    public PageResponse<GameSessionSummaryResponse> listSessions(
            SessionFilter filter,
            SessionSort sort,
            int page,
            int size
    ) {
        // Rejects a negative page or a size below 1 before any peer is asked.
        Pageable pageable = PageRequest.of(page, size);

        // A session is only ever on one instance, but if this instance's
        // address isn't configured, it answers for itself too: keep the
        // summary read directly.
        Map<SessionCode, GameSessionSummaryResponse> byId = new LinkedHashMap<>();
        Stream.concat(gameSessionService.localSummaries().stream(), peerSessions.fetch().stream())
                .forEach(summary -> byId.putIfAbsent(summary.id(), summary));

        List<GameSessionSummaryResponse> matching = byId.values()
                .stream()
                .filter(filter::matches)
                .sorted(sort.comparator())
                .toList();

        int from = (int) Math.min(pageable.getOffset(), matching.size());
        int to = Math.min(from + size, matching.size());

        return PageResponse.from(new PageImpl<>(matching.subList(from, to), pageable, matching.size()));
    }
}
