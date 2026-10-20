package com.nickneim.jeopardy.session.lobby;

import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/** Fetches an instance's sessions from its management port over HTTP. */
@Component
class HttpPeerClient implements PeerClient {

    private static final TypeReference<List<GameSessionSummaryResponse>> SUMMARIES =
            new TypeReference<>() {};

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

        return jsonMapper.readValue(response.body(), SUMMARIES);
    }
}
