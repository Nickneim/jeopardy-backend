package com.nickneim.jeopardy.session.integration;

import com.nickneim.jeopardy.TestcontainersConfiguration;
import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.dto.AdjustScoreRequest;
import com.nickneim.jeopardy.session.dto.AnswerClueRequest;
import com.nickneim.jeopardy.session.dto.RuleRequest;
import com.nickneim.jeopardy.session.dto.SessionSettingsRequest;
import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.HostMode;
import com.nickneim.jeopardy.session.security.PlayerAuthenticationInterceptor;
import com.nickneim.jeopardy.session.security.PlayerPrincipal;
import com.nickneim.jeopardy.session.service.GameSessionService;
import com.nickneim.jeopardy.session.service.TestGameSessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Trebek session over STOMP: Trebek's private view on /user/queue/trebek,
 * and a public topic that never carries its secrets.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TrebekWebSocketIntegrationTest {

    private static final Long CLUE_ID = 10L;
    private static final String QUESTION = "Secret question on the Danube";
    private static final String ANSWER = "Bratislava";
    private static final String SUBMITTED = "Pressburg perhaps";

    @Autowired
    private GameSessionService service;

    @LocalServerPort
    private int port;

    private WebSocketStompClient stompClient;

    private GameSession session;
    private GameSessionPlayer trebek;
    private GameSessionPlayer ana;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());

        session = TestGameSessions.createSession(service, new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(new BoardCategory("Capitals", List.of(
                        new BoardClue(CLUE_ID, 1, QUESTION, ANSWER, 200, false),
                        new BoardClue(11L, 2, "Another question", "Another answer", 400, false)
                ))))
        )));
        trebek = service.joinSession(session.getId(), "Alex");
        ana = service.joinSession(session.getId(), "Ana");
        service.updateSettings(as(trebek), new SessionSettingsRequest(null, HostMode.TREBEK, null));
        service.startGame(as(trebek));
        TestGameSessions.finishRoundIntro(service, session.getId());
    }

    @AfterEach
    void tearDown() {
        stompClient.stop();
    }

    @Test
    void trebek_getsTheirViewAsSoonAsTheySubscribe() throws Exception {
        service.chooseClue(as(ana), CLUE_ID);

        StompSession trebekConnection = connect(trebek.getToken());
        BlockingQueue<JsonNode> views = subscribe(trebekConnection, "/user/queue/trebek");

        JsonNode view = await(views);
        assertEquals(session.getVersion(), view.get("version").asLong());
        assertEquals(QUESTION, view.get("clue").get("question").asString());
        assertEquals(ANSWER, view.get("clue").get("answer").asString());
    }

    @Test
    void aPlayerSubscribingToTrebeksView_receivesNothing() throws Exception {
        service.chooseClue(as(ana), CLUE_ID);

        StompSession anaConnection = connect(ana.getToken());
        BlockingQueue<JsonNode> views = subscribe(anaConnection, "/user/queue/trebek");
        BlockingQueue<JsonNode> errors = subscribe(anaConnection, "/user/queue/errors");
        // An action of hers that fails: its error proves the subscriptions
        // before it were handled.
        anaConnection.send("/app/sessions/" + session.getId() + "/advance", new byte[0]);
        assertEquals(GameErrorCode.NOT_HOST.name(), await(errors).get("code").asString());

        service.advance(as(trebek));

        assertNull(views.poll(500, TimeUnit.MILLISECONDS));
    }

    @Test
    void thePublicTopic_neverCarriesTheHiddenClueOrThePendingAnswer() throws Exception {
        StompSession trebekConnection = connect(trebek.getToken());
        BlockingQueue<JsonNode> views = subscribe(trebekConnection, "/user/queue/trebek");
        await(views);
        StompSession anaConnection = connect(ana.getToken());
        BlockingQueue<JsonNode> topic = subscribe(anaConnection, "/topic/sessions/" + session.getId());
        BlockingQueue<JsonNode> errors = subscribe(anaConnection, "/user/queue/errors");
        anaConnection.send("/app/sessions/" + session.getId() + "/advance", new byte[0]);
        await(errors);

        // A picked clue: everyone sees which, only Trebek sees what.
        service.chooseClue(as(ana), CLUE_ID);
        JsonNode picked = awaitState(topic, "CLUE_PICKED");
        assertTrue(picked.get("currentClue").get("dailyDouble").isNull());
        assertEquals(QUESTION, latest(views, picked.get("version").asLong()).get("clue").get("question").asString());

        String app = "/app/sessions/" + session.getId() + "/";
        trebekConnection.send(app + "adjust-score", new AdjustScoreRequest(ana.getId(), 50));
        JsonNode adjusted = awaitState(topic, "CLUE_PICKED");
        assertEquals(50, adjusted.get("players").get(0).get("score").asInt());

        trebekConnection.send(app + "advance", new byte[0]);
        trebekConnection.send(app + "advance", new byte[0]);
        awaitState(topic, "WAITING_FOR_BUZZER");
        anaConnection.send(app + "buzz", new byte[0]);
        awaitState(topic, "WAITING_FOR_ANSWER");
        anaConnection.send(app + "answer", new AnswerClueRequest(SUBMITTED));

        JsonNode waiting = awaitState(topic, "WAITING_FOR_RULING");
        assertEquals(ana.getId(), waiting.get("playerAnswering").asLong());

        JsonNode view = latest(views, waiting.get("version").asLong());
        assertEquals(SUBMITTED, view.get("pendingAnswer").get("answer").asString());
        assertFalse(view.get("pendingAnswer").get("suggestedCorrect").asBoolean());

        trebekConnection.send(app + "rule", new RuleRequest(true));

        JsonNode ruled = await(topic);
        assertEquals("REVEALING_ANSWER", ruled.get("state").asString());
        JsonNode clueAnswer = ruled.get("clueAnswers").get(0);
        assertEquals(SUBMITTED, clueAnswer.get("answer").asString());
        assertTrue(clueAnswer.get("correct").asBoolean());
        assertTrue(clueAnswer.get("ruledByTrebek").asBoolean());
        assertEquals(250, ruled.get("players").get(0).get("score").asInt());
    }

    @Test
    void adjustScore_outOfRange_isAnInvalidRequest() throws Exception {
        StompSession trebekConnection = connect(trebek.getToken());
        BlockingQueue<JsonNode> errors = subscribe(trebekConnection, "/user/queue/errors");

        trebekConnection.send("/app/sessions/" + session.getId() + "/adjust-score", new AdjustScoreRequest(ana.getId(), 100_001));

        JsonNode error = await(errors);
        assertEquals(GameErrorCode.INVALID_REQUEST.name(), error.get("code").asString());
        assertEquals("amount must be between -100000 and 100000", error.get("message").asString());
    }

    // The next broadcast in this state. Every broadcast until then (all of
    // them before the answer is ruled on) is checked for secrets: the
    // hidden question, the correct response and the pending answer.
    private JsonNode awaitState(BlockingQueue<JsonNode> topic, String state) throws InterruptedException {
        while (true) {
            JsonNode response = await(topic);
            String json = response.toString();

            if (!response.get("state").asString().equals("REVEALING_CLUE")
                    && !response.get("state").asString().equals("WAITING_FOR_BUZZER")
                    && !response.get("state").asString().equals("WAITING_FOR_ANSWER")
                    && !response.get("state").asString().equals("WAITING_FOR_RULING")) {
                assertFalse(json.contains(QUESTION), "leaked the question: " + json);
            }
            assertFalse(json.contains(ANSWER), "leaked the answer: " + json);
            assertFalse(json.contains(SUBMITTED), "leaked the pending answer: " + json);

            if (response.get("state").asString().equals(state)) {
                return response;
            }
        }
    }

    // Trebek's view for this version: the views come in version order.
    private JsonNode latest(BlockingQueue<JsonNode> views, long version) throws InterruptedException {
        while (true) {
            JsonNode view = await(views);
            if (view.get("version").asLong() == version) {
                return view;
            }
        }
    }

    private PlayerPrincipal as(GameSessionPlayer player) {
        return TestGameSessions.player(service, session.getId(), player.getId());
    }

    private StompSession connect(String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add(PlayerAuthenticationInterceptor.TOKEN_HEADER, token);

        return stompClient
                .connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connectHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
    }

    private static BlockingQueue<JsonNode> subscribe(StompSession connection, String destination) {
        BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();

        connection.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.add((JsonNode) payload);
            }
        });

        return messages;
    }

    private static JsonNode await(BlockingQueue<JsonNode> messages) throws InterruptedException {
        JsonNode message = messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(message, "nothing arrived");
        return message;
    }
}
