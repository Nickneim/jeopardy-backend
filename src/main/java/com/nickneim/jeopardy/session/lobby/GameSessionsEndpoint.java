package com.nickneim.jeopardy.session.lobby;

import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import com.nickneim.jeopardy.session.service.GameSessionService;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The sessions this instance holds, at {@code /actuator/gamesessions}, for
 * the other instances to build the lobby list (PeerSessions). It's an
 * Actuator endpoint so that in Kubernetes it's on the management port, which
 * no Service or Ingress exposes. Without a separate management port it's on
 * the main one, which is harmless: it holds the same public summaries as the
 * lobby list.
 */
@Component
@Endpoint(id = "gamesessions")
public class GameSessionsEndpoint {

    private final GameSessionService gameSessionService;

    public GameSessionsEndpoint(GameSessionService gameSessionService) {
        this.gameSessionService = gameSessionService;
    }

    @ReadOperation
    public List<GameSessionSummaryResponse> sessions() {
        return gameSessionService.localSummaries();
    }
}
