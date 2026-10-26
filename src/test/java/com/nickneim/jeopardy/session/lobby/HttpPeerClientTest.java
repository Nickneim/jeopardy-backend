package com.nickneim.jeopardy.session.lobby;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import com.nickneim.jeopardy.session.model.GameSessionState;
import com.nickneim.jeopardy.session.model.SessionCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpPeerClientTest {

    private static final URI SOURCE = URI.create("http://10.0.0.2:8081/actuator/gamesessions");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final HttpPeerClient client = new HttpPeerClient(
            jsonMapper,
            new SessionLobbyProperties("unused", "", 8081, Duration.ofSeconds(1))
    );

    @Test
    void leavesOutOnlyTheSummariesItCantRead() throws IOException {
        GameSessionSummaryResponse readable = summary();

        // As from a newer instance during a rolling update.
        ObjectNode unknownState = jsonMapper.valueToTree(summary());
        unknownState.put("state", "SOME_NEW_STATE");
        ObjectNode noCreatedAt = jsonMapper.valueToTree(summary());
        noCreatedAt.remove("createdAt");

        ArrayNode body = jsonMapper.createArrayNode()
                .add(unknownState)
                .add(jsonMapper.valueToTree(readable))
                .add(noCreatedAt)
                .addNull();

        assertEquals(List.of(readable), client.read(jsonMapper.writeValueAsString(body), SOURCE));
    }

    @Test
    void rejectsAnythingButAList() {
        assertThrows(IOException.class, () -> client.read("{\"error\": \"nope\"}", SOURCE));
        assertThrows(IOException.class, () -> client.read("<html>", SOURCE));
    }

    private static GameSessionSummaryResponse summary() {
        return new GameSessionSummaryResponse(
                SessionCode.random(),
                735L,
                "Show #735",
                GameSessionState.WAITING_FOR_START,
                GameRound.JEOPARDY,
                1,
                "Nico",
                Instant.parse("2026-10-09T12:00:00Z"),
                true,
                true,
                0
        );
    }
}
