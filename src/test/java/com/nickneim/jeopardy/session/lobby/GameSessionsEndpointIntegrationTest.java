package com.nickneim.jeopardy.session.lobby;

import com.nickneim.jeopardy.TestcontainersConfiguration;
import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.service.GameSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One instance reading another's sessions, the way the lobby does with
 * several instances: the endpoint's JSON must read back into the same
 * summaries. Without a separate management port, the endpoint is on the
 * server port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class GameSessionsEndpointIntegrationTest {

    @Autowired
    private GameSessionService gameSessionService;

    @Autowired
    private JsonMapper jsonMapper;

    @LocalServerPort
    private int port;

    private GameSession session;

    @AfterEach
    void tearDown() {
        gameSessionService.removeSession(session.getId());
    }

    @Test
    void anotherInstanceReadsTheSessionsThisOneHolds() throws Exception {
        session = gameSessionService.createSession(new GameBoard(7L, "Show #7", List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(new BoardCategory(
                        "HISTORY",
                        List.of(new BoardClue(10L, 1, "Question", "Answer", 200, false))
                )))
        )));
        gameSessionService.joinSession(session.getId(), "Nico");

        HttpPeerClient client = new HttpPeerClient(
                jsonMapper,
                new SessionLobbyProperties("unused", "", port, Duration.ofSeconds(5))
        );
        List<GameSessionSummaryResponse> fetched = client.fetch("127.0.0.1");

        GameSessionSummaryResponse expected = gameSessionService.localSummaries()
                .stream()
                .filter(summary -> summary.id().equals(session.getId()))
                .findFirst()
                .orElseThrow();
        assertTrue(fetched.contains(expected), () -> "fetched " + fetched);
    }
}
