package com.nickneim.jeopardy.session.integration;

import com.nickneim.jeopardy.TestcontainersConfiguration;
import com.nickneim.jeopardy.session.dto.AnswerClueRequest;
import com.nickneim.jeopardy.session.dto.ChooseClueRequest;
import com.nickneim.jeopardy.session.dto.CreateGameSessionRequest;
import com.nickneim.jeopardy.session.dto.CreateGameSessionResponse;
import com.nickneim.jeopardy.session.dto.GameSessionResponse;
import com.nickneim.jeopardy.session.dto.JoinSessionRequest;
import com.nickneim.jeopardy.session.dto.JoinSessionResponse;
import com.nickneim.jeopardy.session.dto.PlaceDailyDoubleWagerRequest;
import com.nickneim.jeopardy.session.model.GameSessionState;
import com.nickneim.jeopardy.session.security.PlayerAuthenticationInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.Map;
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
@Import(TestcontainersConfiguration.class)
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
    void creatingSessionFromGameWithoutClues_returnsUnprocessableContent() {
        Long gameId = jdbcTemplate.queryForObject(
                "SELECT id FROM game WHERE title = ?",
                Long.class,
                GAME_TITLE + " Without Clues"
        );

        restTestClient.post()
                .uri("/api/game-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateGameSessionRequest(gameId))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .json("""
                    {
                        "status": 422,
                        "message": "Game %d has no clues and can't be played"
                    }
                    """.formatted(gameId), JsonCompareMode.STRICT);
    }

    @Test
    void joiningWithBlankName_returnsApiErrorNamingTheField() {
        Long gameId = jdbcTemplate.queryForObject(
                "SELECT id FROM game WHERE title = ?",
                Long.class,
                GAME_TITLE
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

        restTestClient.post()
                .uri("/api/game-sessions/" + sessionId + "/players")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new JoinSessionRequest(" "))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .json("""
                    {
                        "status": 400,
                        "message": "playerName must not be blank"
                    }
                    """, JsonCompareMode.STRICT);
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

        // Joining returns the player's id and their secret token...
        JoinSessionResponse joined = restTestClient.post()
                .uri("/api/game-sessions/" + sessionId + "/players")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new JoinSessionRequest("Nico"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(JoinSessionResponse.class)
                .returnResult()
                .getResponseBody();

        // ...which identifies the player for the rest of the game.
        StompSession stompSession = connect(joined.token());

        BlockingQueue<GameSessionResponse> messages =
                subscribeToSession(stompSession, sessionId);

        send(stompSession, sessionId, "start", Map.of());

        GameSessionResponse started = awaitMessage(messages);

        assertEquals(
                GameSessionState.CHOOSING_CLUE,
                started.state()
        );
        assertEquals(
                joined.playerId(),
                started.playerChoosingClue()
        );

        // A client catching up (e.g. after a refresh) fetches the same state.
        GameSessionResponse fetched = restTestClient.get()
                .uri("/api/game-sessions/" + sessionId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(GameSessionResponse.class)
                .returnResult()
                .getResponseBody();

        assertEquals(started.version(), fetched.version());
        assertEquals(GameSessionState.CHOOSING_CLUE, fetched.state());

        send(
                stompSession,
                sessionId,
                "choose-clue",
                new ChooseClueRequest(dailyDoubleClueId)
        );

        assertEquals(
                GameSessionState.WAITING_FOR_DAILY_DOUBLE_WAGER,
                awaitMessage(messages).state()
        );

        send(
                stompSession,
                sessionId,
                "place-daily-double-wager",
                // The fixture's Daily Double is the round's highest clue (400).
                new PlaceDailyDoubleWagerRequest(300)
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
                new AnswerClueRequest("John Adams")
        );

        GameSessionResponse answered = awaitMessage(messages);

        assertEquals(
                GameSessionState.REVEALING_ANSWER,
                answered.state()
        );
        assertEquals(
                300,
                answered.players().getFirst().score()
        );
    }

    private StompSession connect(String playerToken)
            throws Exception {

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add(
                PlayerAuthenticationInterceptor.TOKEN_HEADER,
                playerToken
        );

        return stompClient
                .connectAsync(
                        "ws://localhost:" + port + "/ws",
                        new WebSocketHttpHeaders(),
                        connectHeaders,
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
