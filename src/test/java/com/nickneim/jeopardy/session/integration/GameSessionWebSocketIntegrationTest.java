package com.nickneim.jeopardy.session.integration;

import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.session.dto.GameSessionResponse;
import com.nickneim.jeopardy.session.dto.JoinSessionRequest;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionState;
import com.nickneim.jeopardy.session.service.GameSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
class GameSessionWebSocketIntegrationTest {

    @Autowired
    private GameSessionService gameSessionService;

    @LocalServerPort
    private int port;

    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(
                new StandardWebSocketClient()
        );

        stompClient.setMessageConverter(
                new JacksonJsonMessageConverter()
        );
    }

    @AfterEach
    void tearDown() {
        stompClient.stop();
    }

    @Test
    void playerJoiningSession_broadcastsUpdatedSession()
            throws Exception {

        Game game = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "40"
        );

        GameSession session =
                gameSessionService.createSession(game);

        StompSession stompSession =
                connect();

        BlockingQueue<GameSessionResponse> receivedMessages =
                new LinkedBlockingQueue<>();

        subscribeToSession(
                stompSession,
                session.getId(),
                receivedMessages
        );

        JoinSessionRequest joinSessionRequest =
                new JoinSessionRequest(
                        "Nico"
                );

        joinSession(
                stompSession,
                session.getId(),
                joinSessionRequest
        );

        GameSessionResponse response =
                receivedMessages.poll(
                        5,
                        TimeUnit.SECONDS
                );

        assertNotNull(response);
        assertEquals(session.getId(), response.id());
        assertEquals(
                GameSessionState.CHOOSING_CLUE,
                response.state()
        );

        assertEquals(1, response.players().size());
        assertEquals(
                joinSessionRequest.playerName(),
                response.players().getFirst().name()
        );
        assertEquals(
                0,
                response.players().getFirst().score()
        );
    }

    private StompSession connect()
            throws Exception {

        return stompClient
                .connectAsync(
                        "ws://localhost:" + port + "/ws",
                        new StompSessionHandlerAdapter() {}
                )
                .get(5, TimeUnit.SECONDS);
    }

    private void subscribeToSession(
            StompSession stompSession,
            UUID sessionId,
            BlockingQueue<
                    GameSessionResponse
                    > receivedMessages
    ) {
        stompSession.subscribe(
                "/topic/sessions/" + sessionId,
                new StompFrameHandler() {

                    @Override
                    public Type getPayloadType(
                            StompHeaders headers
                    ) {
                        return GameSessionResponse.class;
                    }

                    @Override
                    public void handleFrame(
                            StompHeaders headers,
                            Object payload
                    ) {
                        receivedMessages.add(
                                (GameSessionResponse) payload
                        );
                    }
                }
        );
    }

    private void joinSession(
            StompSession stompSession,
            UUID sessionId,
            JoinSessionRequest joinSessionRequest
    ) {
        stompSession.send(
                "/app/sessions/" + sessionId + "/join",
                joinSessionRequest
        );
    }
}
