package com.nickneim.jeopardy.session.integration;

import com.nickneim.jeopardy.session.dto.AnswerClueRequest;
import com.nickneim.jeopardy.session.dto.ChooseClueRequest;
import com.nickneim.jeopardy.session.dto.CreateGameSessionRequest;
import com.nickneim.jeopardy.session.dto.CreateGameSessionResponse;
import com.nickneim.jeopardy.session.dto.GameSessionResponse;
import com.nickneim.jeopardy.session.dto.JoinSessionRequest;
import com.nickneim.jeopardy.session.dto.PlaceDailyDoubleWagerRequest;
import com.nickneim.jeopardy.session.dto.StartGameRequest;
import com.nickneim.jeopardy.session.model.GameSessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Plays a game that is loaded from the database, the same way a real client
 * does: create the session over REST, then play it over STOMP.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@Sql(
        scripts = "/testdata/game-session-integration-test-data.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
@Sql(
        scripts = "/testdata/game-session-integration-test-data-cleanup.sql",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD
)
class GameSessionEndToEndIntegrationTest {

    private static final String GAME_TITLE =
            "Game Session Integration Test Game";

    @Autowired
    private RestTestClient restTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void sessionCreatedFromDatabaseGame_canPlayDailyDouble()
            throws Exception {

        Long gameId = jdbcTemplate.queryForObject(
                "SELECT id FROM game WHERE title = ?",
                Long.class,
                GAME_TITLE
        );

        Long dailyDoubleClueId = jdbcTemplate.queryForObject(
                "SELECT clue_id FROM game_daily_double WHERE game_id = ?",
                Long.class,
                gameId
        );

        UUID sessionId = restTestClient.post()
                .uri("/api/game-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateGameSessionRequest(gameId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateGameSessionResponse.class)
                .returnResult()
                .getResponseBody()
                .id();

        StompSession stompSession = connect();

        BlockingQueue<GameSessionResponse> messages =
                subscribeToSession(stompSession, sessionId);

        send(stompSession, sessionId, "join", new JoinSessionRequest("Nico"));

        GameSessionResponse joined = awaitMessage(messages);
        Long playerId = joined.players().getFirst().id();

        send(stompSession, sessionId, "start", new StartGameRequest());

        assertEquals(
                GameSessionState.CHOOSING_CLUE,
                awaitMessage(messages).state()
        );

        send(
                stompSession,
                sessionId,
                "choose-clue",
                new ChooseClueRequest(playerId, dailyDoubleClueId)
        );

        assertEquals(
                GameSessionState.WAITING_FOR_DAILY_DOUBLE_WAGER,
                awaitMessage(messages).state()
        );

        send(
                stompSession,
                sessionId,
                "place-daily-double-wager",
                new PlaceDailyDoubleWagerRequest(playerId, 500)
        );

        assertEquals(
                GameSessionState.REVEALING_CLUE,
                awaitMessage(messages).state()
        );

        // The clue-reveal timer moves straight to the chooser's answer.
        assertEquals(
                GameSessionState.WAITING_FOR_ANSWER,
                awaitMessage(messages).state()
        );

        send(
                stompSession,
                sessionId,
                "answer",
                new AnswerClueRequest(playerId, "John Adams")
        );

        GameSessionResponse answered = awaitMessage(messages);

        assertEquals(
                GameSessionState.REVEALING_ANSWER,
                answered.state()
        );
        assertEquals(
                500,
                answered.players().getFirst().score()
        );
    }

    private StompSession connect()
            throws Exception {

        return stompClient
                .connectAsync(
                        "ws://localhost:" + port + "/ws",
                        new StompSessionHandlerAdapter() {}
                )
                .get(
                        5,
                        TimeUnit.SECONDS
                );
    }

    private BlockingQueue<GameSessionResponse> subscribeToSession(
            StompSession stompSession,
            UUID sessionId
    ) {
        BlockingQueue<GameSessionResponse> messages =
                new LinkedBlockingQueue<>();

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
                        messages.add(
                                (GameSessionResponse) payload
                        );
                    }
                }
        );

        return messages;
    }

    private GameSessionResponse awaitMessage(
            BlockingQueue<GameSessionResponse> messages
    ) throws InterruptedException {

        GameSessionResponse response =
                messages.poll(
                        5,
                        TimeUnit.SECONDS
                );

        assertNotNull(response);

        return response;
    }

    private void send(
            StompSession stompSession,
            UUID sessionId,
            String action,
            Object request
    ) {
        stompSession.send(
                "/app/sessions/" + sessionId + "/" + action,
                request
        );
    }
}
