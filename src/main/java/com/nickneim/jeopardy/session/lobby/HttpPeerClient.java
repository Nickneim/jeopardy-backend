package com.nickneim.jeopardy.session.lobby;

import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/** Fetches an instance's sessions from its management port over HTTP. */
@Component
class HttpPeerClient implements PeerClient {

    private static final Logger log = LoggerFactory.getLogger(HttpPeerClient.class);

    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final SessionLobbyProperties properties;

    HttpPeerClient(JsonMapper jsonMapper, SessionLobbyProperties properties) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .build();
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    @Override
    public List<GameSessionSummaryResponse> fetch(String address)
            throws IOException, InterruptedException {

        // An IPv6 address needs brackets in a URL.
        String host = address.contains(":") ? "[" + address + "]" : address;
        URI uri = URI.create("http://" + host + ":" + properties.peerPort() + "/actuator/gamesessions");

        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(uri).timeout(properties.timeout()).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + uri);
        }

        return read(response.body(), uri);
    }

    /**
     * Reads each summary on its own, leaving out any this instance can't
     * read (during a rolling update, a newer instance may send a state this
     * one doesn't know) or that lacks what the lobby sorts and filters by, so
     * that one such summary doesn't hide the instance's other sessions.
     */
    List<GameSessionSummaryResponse> read(String body, URI source) throws IOException {
        JsonNode tree;
        try {
            tree = jsonMapper.readTree(body);
        } catch (JacksonException exception) {
            throw new IOException("Unreadable sessions from " + source, exception);
        }
        if (!tree.isArray()) {
            throw new IOException("Expected a list of sessions from " + source);
        }

        List<GameSessionSummaryResponse> summaries = new ArrayList<>();
        String firstProblem = null;
        for (JsonNode element : tree) {
            String problem;
            try {
                GameSessionSummaryResponse summary = jsonMapper.treeToValue(element, GameSessionSummaryResponse.class);
                if (summary.id() != null && summary.state() != null && summary.createdAt() != null) {
                    summaries.add(summary);
                    continue;
                }
                problem = "missing id, state or createdAt";
            } catch (JacksonException exception) {
                problem = exception.getOriginalMessage();
            }
            if (firstProblem == null) {
                firstProblem = problem;
            }
        }

        if (firstProblem != null) {
            log.warn("Left out {} of {} sessions from {}: {}",
                    tree.size() - summaries.size(), tree.size(), source, firstProblem);
        }
        return summaries;
    }
}
