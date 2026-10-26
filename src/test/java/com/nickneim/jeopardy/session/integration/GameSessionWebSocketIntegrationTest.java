package com.nickneim.jeopardy.session.integration;

import com.nickneim.jeopardy.TestcontainersConfiguration;
import com.nickneim.jeopardy.config.WebSocketConfig;
import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.dto.AnswerClueRequest;
import com.nickneim.jeopardy.session.dto.AnswerFinalJeopardyRequest;
import com.nickneim.jeopardy.session.dto.ChooseClueRequest;
import com.nickneim.jeopardy.session.dto.ClueAnswerResponse;
import com.nickneim.jeopardy.session.dto.ClueSlotResponse;
import com.nickneim.jeopardy.session.dto.ClueSlotStatus;
import com.nickneim.jeopardy.session.dto.GameSessionErrorResponse;
import com.nickneim.jeopardy.session.dto.GameSessionResponse;
import com.nickneim.jeopardy.session.dto.PlaceDailyDoubleWagerRequest;
import com.nickneim.jeopardy.session.dto.PlaceFinalJeopardyWagerRequest;
import com.nickneim.jeopardy.session.dto.PlayerResponse;
import com.nickneim.jeopardy.session.dto.SessionSettingsRequest;
import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import com.nickneim.jeopardy.session.exception.SessionNotJoinableException;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionState;
import com.nickneim.jeopardy.session.model.SessionCloseReason;
import com.nickneim.jeopardy.session.security.PlayerAuthenticationInterceptor;
import com.nickneim.jeopardy.session.service.GameSessionService;
import com.nickneim.jeopardy.session.model.SessionCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import(TestcontainersConfiguration.class)
class GameSessionWebSocketIntegrationTest {

    private static final Long DAILY_DOUBLE_CLUE_ID = 12L;

    @Autowired
    private GameSessionService gameSessionService;

    @Autowired
    private SimpleBrokerMessageHandler simpleBrokerMessageHandler;

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

    // -------------------------------------------------------------------------
    // Connecting with a player token
    // -------------------------------------------------------------------------

    @Test
    void connectingWithValidPlayerToken_succeeds()
            throws Exception {

        GameSession session = createSession();

        GameSessionPlayer player =
                gameSessionService.joinSession(session.getId(), "Nico");

        StompSession stompSession = connect(player.getToken());

        assertTrue(stompSession.isConnected());
    }

    @Test
    void connectingWithUnknownPlayerToken_isRejected() {
        assertThrows(
                ExecutionException.class,
                () -> connect("forged-token")
        );
    }

    // -------------------------------------------------------------------------
    // Browser origins
    // -------------------------------------------------------------------------

    @Test
    void connectingFromTheFrontendOrigin_succeeds()
            throws Exception {

        StompSession stompSession =
                connectFromOrigin("http://localhost:5173");

        assertTrue(stompSession.isConnected());
    }

    @Test
    void connectingFromAnotherOrigin_isRejected() {
        assertThrows(
                ExecutionException.class,
                () -> connectFromOrigin("https://evil.example")
        );
    }

    // -------------------------------------------------------------------------
    // Heartbeats
    // -------------------------------------------------------------------------

    // Without heartbeats a connection that dies without closing is never
    // noticed, so the server must agree to them when the client asks.
    @Test
    void connectingWithHeartbeats_agreesToThem()
            throws Exception {

        // The client needs a scheduler of its own to send heartbeats.
        ThreadPoolTaskScheduler clientScheduler = new ThreadPoolTaskScheduler();
        clientScheduler.initialize();
        stompClient.setTaskScheduler(clientScheduler);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.setHeartbeat(new long[] {5_000, 5_000});

        CompletableFuture<StompHeaders> connected = new CompletableFuture<>();

        stompClient
                .connectAsync(
                        "ws://localhost:" + port + "/ws",
                        new WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {
                            @Override
                            public void afterConnected(StompSession session, StompHeaders headers) {
                                connected.complete(headers);
                            }
                        }
                )
                .get(
                        5,
                        TimeUnit.SECONDS
                );

        try {
            assertArrayEquals(
                    new long[] {WebSocketConfig.HEARTBEAT_MILLIS, WebSocketConfig.HEARTBEAT_MILLIS},
                    connected.get(5, TimeUnit.SECONDS).getHeartbeat()
            );
        } finally {
            stompClient.stop();
            clientScheduler.shutdown();
        }
    }

    // -------------------------------------------------------------------------
    // Joining
    // -------------------------------------------------------------------------

    @Test
    void playerJoiningSession_broadcastsUpdatedSession()
            throws Exception {

        GameSession session = createSession();

        // Anyone can watch a session; no token needed to subscribe.
        StompSession stompSession = connect();

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        // The join below doesn't go through this connection, so its broadcast
        // can beat the SUBSCRIBE. Probing another session on the same
        // connection proves the subscription is registered without adding
        // players to this one.
        awaitSubscriptions(stompSession, createSession().getId());

        gameSessionService.joinSession(
                session.getId(),
                "Nico"
        );

        GameSessionResponse response =
                awaitMessage(receivedMessages);

        assertEquals(
                session.getId(),
                response.id()
        );

        assertEquals(
                GameSessionState.WAITING_FOR_START,
                response.state()
        );

        assertEquals(
                1,
                response.players().size()
        );

        assertEquals(
                "Nico",
                response.players().getFirst().name()
        );

        assertEquals(
                0,
                response.players().getFirst().score()
        );
    }

    // -------------------------------------------------------------------------
    // Starting
    // -------------------------------------------------------------------------

    @Test
    void startingGame_broadcastsUpdatedSession()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        startGame(
                stompSession,
                session.getId()
        );

        GameSessionResponse response =
                awaitMessage(receivedMessages);

        assertEquals(
                session.getId(),
                response.id()
        );

        assertEquals(
                GameSessionState.CHOOSING_CLUE,
                response.state()
        );

        assertEquals(
                1,
                response.players().size()
        );

        assertEquals(
                "Nico",
                response.players().getFirst().name()
        );

        assertEquals(
                0,
                response.players().getFirst().score()
        );
    }

    @Test
    void startingAlreadyStartedGame_returnsError()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        gameSessionService.startGame(
                session.getId(),
                session.getPlayers().getFirst().getId()
        );

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        startGame(
                stompSession,
                session.getId()
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                GameErrorCode.INVALID_STATE,
                error.code()
        );
    }

    @Test
    void actingInARemovedSession_returnsError()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        gameSessionService.removeSession(session.getId());

        startGame(
                stompSession,
                session.getId()
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                GameErrorCode.SESSION_NOT_FOUND,
                error.code()
        );
    }

    @Test
    void actingWithAMalformedSessionCode_returnsError()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        stompSession.send("/app/sessions/not-a-code/start", Map.of());

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(GameErrorCode.INVALID_REQUEST, error.code());
        assertEquals("Invalid session code: expected 5 letters and digits", error.message());
    }

    @Test
    void startingGameWithTokenFromAnotherSession_returnsError()
            throws Exception {

        GameSession session = createSessionWithPlayer();
        GameSession otherSession = createSessionWithPlayer();

        StompSession stompSession = connect(tokenOf(otherSession));

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        startGame(
                stompSession,
                session.getId()
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                GameErrorCode.PLAYER_NOT_IN_SESSION,
                error.code()
        );
    }

    @Test
    void startingGameWithoutToken_returnsError()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        startGame(
                stompSession,
                session.getId()
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                GameErrorCode.UNAUTHENTICATED,
                error.code()
        );
    }

    @Test
    void reconnectingWithTheSameToken_keepsThePlayersIdentity()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        connect(tokenOf(session)).disconnect();

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        startGame(
                stompSession,
                session.getId()
        );

        GameSessionResponse response =
                awaitMessage(receivedMessages);

        assertEquals(
                GameSessionState.CHOOSING_CLUE,
                response.state()
        );
    }

    // -------------------------------------------------------------------------
    // Choosing a clue
    // -------------------------------------------------------------------------

    @Test
    void choosingClue_broadcastsUpdatedSession()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        gameSessionService.startGame(
                session.getId(),
                session.getPlayers().getFirst().getId()
        );

        Long clueId = 10L;

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        chooseClue(
                stompSession,
                session.getId(),
                new ChooseClueRequest(clueId
                )
        );

        GameSessionResponse response =
                awaitMessage(receivedMessages);

        assertEquals(
                session.getId(),
                response.id()
        );

        assertEquals(
                GameSessionState.REVEALING_CLUE,
                response.state()
        );

        assertEquals(
                List.of(
                        new ClueSlotResponse(1, 10L, 200, ClueSlotStatus.PLAYED),
                        new ClueSlotResponse(2, 11L, 400, ClueSlotStatus.AVAILABLE)
                ),
                response.board().getFirst().slots()
        );

        // The clue reveal lasts 3 seconds and has only just started.
        assertEquals(3000L, response.phaseDurationMs());
        assertTrue(
                response.phaseRemainingMs() > 0 && response.phaseRemainingMs() <= 3000,
                "phaseRemainingMs: " + response.phaseRemainingMs()
        );
    }

    @Test
    void choosingClueAsAnotherPlayer_returnsError()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        GameSessionPlayer secondPlayer =
                gameSessionService.joinSession(session.getId(), "John");

        gameSessionService.startGame(
                session.getId(),
                session.getPlayers().getFirst().getId()
        );

        // The first player chooses; the second connects with their own token.
        StompSession stompSession = connect(secondPlayer.getToken());

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        chooseClue(
                stompSession,
                session.getId(),
                new ChooseClueRequest(10L)
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                GameErrorCode.NOT_YOUR_TURN,
                error.code()
        );
    }

    @Test
    void choosingAlreadySelectedClue_returnsError()
            throws Exception {

        GameSession session =
                createSessionWithPlayer();

        gameSessionService.startGame(
                session.getId(),
                session.getPlayers().getFirst().getId()
        );

        Long playerId =
                session.getPlayers().getFirst().getId();

        // Choose the clue.
        gameSessionService.chooseClue(
                session.getId(),
                playerId,
                10L
        );

        // Clue reveal finishes.
        gameSessionService.finishRevealingClue(
                session.getId()
        );

        // Player buzzes.
        gameSessionService.buzz(
                session.getId(),
                playerId
        );

        // Player answers correctly.
        gameSessionService.answerClue(
                session.getId(),
                playerId,
                "Correct answer"
        );

        // Answer reveal finishes.
        gameSessionService.finishRevealingAnswer(
                session.getId()
        );

        // We're now back at CHOOSING_CLUE.
        assertEquals(
                GameSessionState.CHOOSING_CLUE,
                session.getState()
        );

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        chooseClue(
                stompSession,
                session.getId(),
                new ChooseClueRequest(10L
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                GameErrorCode.CLUE_ALREADY_PLAYED,
                error.code()
        );
    }

    @Test
    void choosingClueInWrongState_returnsError()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        chooseClue(
                stompSession,
                session.getId(),
                new ChooseClueRequest(10L
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                GameErrorCode.INVALID_STATE,
                error.code()
        );
    }

    @Test
    void choosingUnavailableClue_returnsError()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        gameSessionService.startGame(
                session.getId(),
                session.getPlayers().getFirst().getId()
        );

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        chooseClue(
                stompSession,
                session.getId(),
                new ChooseClueRequest(999L
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                GameErrorCode.CLUE_NOT_FOUND,
                error.code()
        );
    }

    // -------------------------------------------------------------------------
    // Buzzing
    // -------------------------------------------------------------------------

    @Test
    void playerBuzzing_broadcastsUpdatedSession()
            throws Exception {

        GameSession session =
                createSessionWithPlayer();

        gameSessionService.startGame(
                session.getId(),
                session.getPlayers().getFirst().getId()
        );

        Long playerId =
                session.getPlayers().getFirst().getId();

        gameSessionService.chooseClue(
                session.getId(),
                playerId,
                10L
        );

        gameSessionService.finishRevealingClue(
                session.getId()
        );

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        buzz(
                stompSession,
                session.getId()
        );

        GameSessionResponse response =
                awaitMessage(receivedMessages);

        assertEquals(
                GameSessionState.WAITING_FOR_ANSWER,
                response.state()
        );

        assertEquals(
                playerId,
                response.playerAnswering()
        );

        assertEquals(
                Set.of(playerId),
                response.playersLockedOut()
        );
    }

    @Test
    void buzzingInWrongState_returnsError()
            throws Exception {

        GameSession session =
                createSessionWithPlayer();

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        buzz(
                stompSession,
                session.getId()
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                GameErrorCode.INVALID_STATE,
                error.code()
        );
    }

    @Test
    void playerFromAnotherSession_cannotBuzz()
            throws Exception {

        GameSession session =
                createSessionWithPlayer();

        gameSessionService.startGame(
                session.getId(),
                session.getPlayers().getFirst().getId()
        );

        gameSessionService.chooseClue(
                session.getId(),
                session.getPlayers().getFirst().getId(),
                10L
        );

        gameSessionService.finishRevealingClue(
                session.getId()
        );

        GameSession otherSession = createSessionWithPlayer();

        StompSession stompSession = connect(tokenOf(otherSession));

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        buzz(
                stompSession,
                session.getId()
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                GameErrorCode.PLAYER_NOT_IN_SESSION,
                error.code()
        );
    }

    // -------------------------------------------------------------------------
    // Answering
    // -------------------------------------------------------------------------

    @Test
    void answeringCorrectly_broadcastsUpdatedSession()
            throws Exception {

        GameSession session =
                createSessionWithPlayer();

        gameSessionService.startGame(
                session.getId(),
                session.getPlayers().getFirst().getId()
        );

        Long playerId =
                session.getPlayers().getFirst().getId();

        gameSessionService.chooseClue(
                session.getId(),
                playerId,
                10L
        );

        gameSessionService.finishRevealingClue(
                session.getId()
        );

        gameSessionService.buzz(
                session.getId(),
                playerId
        );

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        answer(
                stompSession,
                session.getId(),
                new AnswerClueRequest("Correct answer"
                )
        );

        GameSessionResponse response =
                awaitMessage(receivedMessages);

        assertEquals(
                GameSessionState.REVEALING_ANSWER,
                response.state()
        );

        assertEquals(
                200,
                response.players()
                        .getFirst()
                        .score()
        );
    }

    @Test
    void answeringIncorrectly_broadcastsUpdatedSession()
            throws Exception {

        GameSession session =
                createSessionWithPlayer();

        gameSessionService.startGame(
                session.getId(),
                session.getPlayers().getFirst().getId()
        );

        Long playerId =
                session.getPlayers().getFirst().getId();

        gameSessionService.chooseClue(
                session.getId(),
                playerId,
                10L
        );

        gameSessionService.finishRevealingClue(
                session.getId()
        );

        gameSessionService.buzz(
                session.getId(),
                playerId
        );

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        answer(
                stompSession,
                session.getId(),
                new AnswerClueRequest("Wrong answer"
                )
        );

        GameSessionResponse response =
                awaitMessage(receivedMessages);

        assertEquals(
                GameSessionState.REVEALING_ANSWER,
                response.state()
        );

        assertEquals(
                -200,
                response.players()
                        .getFirst()
                        .score()
        );

        assertEquals(
                List.of(new ClueAnswerResponse(playerId, "Wrong answer", false, false, null, null)),
                response.clueAnswers()
        );
    }

    @Test
    void answeringForThePlayerWhoBuzzed_returnsError()
            throws Exception {

        GameSession session =
                createSessionWithPlayer();

        GameSessionPlayer secondPlayer =
                gameSessionService.joinSession(session.getId(), "John");

        Long playerId =
                session.getPlayers().getFirst().getId();

        gameSessionService.startGame(
                session.getId(),
                playerId
        );

        gameSessionService.chooseClue(
                session.getId(),
                playerId,
                10L
        );

        gameSessionService.finishRevealingClue(
                session.getId()
        );

        gameSessionService.buzz(
                session.getId(),
                playerId
        );

        // The second player tries to answer the first player's buzz. The
        // server knows who they are from their token, whatever they send.
        StompSession stompSession = connect(secondPlayer.getToken());

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        answer(
                stompSession,
                session.getId(),
                new AnswerClueRequest("Correct answer")
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                GameErrorCode.NOT_YOUR_TURN,
                error.code()
        );
    }

    @Test
    void answeringInWrongState_returnsError()
            throws Exception {

        GameSession session =
                createSessionWithPlayer();

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        answer(
                stompSession,
                session.getId(),
                new AnswerClueRequest("Correct answer"
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                GameErrorCode.INVALID_STATE,
                error.code()
        );
    }

    // -------------------------------------------------------------------------
    // Daily Doubles
    // -------------------------------------------------------------------------

    @Test
    void choosingDailyDouble_broadcastsWaitingForWager()
            throws Exception {

        GameSession session = createStartedSessionWithDailyDouble();

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        chooseClue(
                stompSession,
                session.getId(),
                new ChooseClueRequest(DAILY_DOUBLE_CLUE_ID
                )
        );

        GameSessionResponse response =
                awaitMessage(receivedMessages);

        assertEquals(
                GameSessionState.WAITING_FOR_DAILY_DOUBLE_WAGER,
                response.state()
        );
    }

    @Test
    void placingWager_broadcastsRevealingClue()
            throws Exception {

        GameSession session = createSessionWaitingForWager();

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        placeWager(
                stompSession,
                session.getId(),
                new PlaceDailyDoubleWagerRequest(500
                )
        );

        GameSessionResponse response =
                awaitMessage(receivedMessages);

        assertEquals(
                GameSessionState.REVEALING_CLUE,
                response.state()
        );
    }

    @Test
    void placingInvalidWager_returnsError()
            throws Exception {

        GameSession session = createSessionWaitingForWager();

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        placeWager(
                stompSession,
                session.getId(),
                new PlaceDailyDoubleWagerRequest(5000
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                GameErrorCode.INVALID_WAGER,
                error.code()
        );
    }

    @Test
    void placingWagerAsAnotherPlayer_returnsError()
            throws Exception {

        GameSession session = createSessionWaitingForWager();

        GameSessionPlayer secondPlayer =
                gameSessionService.joinSession(session.getId(), "John");

        StompSession stompSession = connect(secondPlayer.getToken());

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        placeWager(
                stompSession,
                session.getId(),
                new PlaceDailyDoubleWagerRequest(500)
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                GameErrorCode.NOT_YOUR_TURN,
                error.code()
        );
    }

    @Test
    void placingWagerInWrongState_returnsError()
            throws Exception {

        GameSession session = createStartedSessionWithDailyDouble();

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        placeWager(
                stompSession,
                session.getId(),
                new PlaceDailyDoubleWagerRequest(500
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                GameErrorCode.INVALID_STATE,
                error.code()
        );
    }

    @Test
    void placingWagerWithMissingWager_returnsError()
            throws Exception {

        GameSession session = createSessionWaitingForWager();

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        stompSession.send(
                "/app/sessions/" + session.getId() + "/place-daily-double-wager",
                Map.of()
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                GameErrorCode.INVALID_REQUEST,
                error.code()
        );
    }

    @Test
    void answeringDailyDouble_appliesWager()
            throws Exception {

        GameSession session = createSessionWaitingForWager();

        Long playerId =
                session.getPlayers().getFirst().getId();

        gameSessionService.placeDailyDoubleWager(
                session.getId(),
                playerId,
                500
        );

        gameSessionService.finishRevealingClue(
                session.getId()
        );

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        answer(
                stompSession,
                session.getId(),
                new AnswerClueRequest("Daily Double answer"
                )
        );

        GameSessionResponse response =
                awaitMessage(receivedMessages);

        assertEquals(
                GameSessionState.REVEALING_ANSWER,
                response.state()
        );

        assertEquals(
                500,
                response.players()
                        .getFirst()
                        .score()
        );
    }

    // -------------------------------------------------------------------------
    // Final Jeopardy
    // -------------------------------------------------------------------------

    @Test
    void placingFinalJeopardyWager_broadcastsThatThePlayerWagered()
            throws Exception {

        GameSession session = createSessionInFinalJeopardy();

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        stompSession.send(
                "/app/sessions/" + session.getId() + "/place-final-jeopardy-wager",
                new PlaceFinalJeopardyWagerRequest(150)
        );

        GameSessionResponse response =
                awaitMessage(receivedMessages);

        assertEquals(
                GameSessionState.FINAL_JEOPARDY_WAGERING,
                response.state()
        );

        assertTrue(
                response.finalJeopardy().players().getFirst().hasWagered()
        );

        // The amount itself stays secret until the reveal.
        assertNull(
                response.finalJeopardy().players().getFirst().wager()
        );
    }

    @Test
    void finalJeopardyWagersSentBackToBack_areAppliedInOrder()
            throws Exception {

        GameSession session = createSessionInFinalJeopardy();
        Long playerId = session.getPlayers().getFirst().getId();

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        // One pair would usually arrive in order by luck; many messages
        // sent without waiting make a reordering show up reliably.
        int lastWager = 200;
        for (int wager = 1; wager <= lastWager; wager++) {
            stompSession.send(
                    "/app/sessions/" + session.getId() + "/place-final-jeopardy-wager",
                    new PlaceFinalJeopardyWagerRequest(wager)
            );
        }

        for (int i = 0; i < lastWager; i++) {
            awaitMessage(receivedMessages);
        }

        assertEquals(lastWager, session.getFinalJeopardy().getWager(playerId));
    }

    @Test
    void placingFinalJeopardyWagerAboveScore_returnsError()
            throws Exception {

        GameSession session = createSessionInFinalJeopardy();

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        stompSession.send(
                "/app/sessions/" + session.getId() + "/place-final-jeopardy-wager",
                new PlaceFinalJeopardyWagerRequest(201)
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                GameErrorCode.INVALID_WAGER,
                error.code()
        );
    }

    @Test
    void placingFinalJeopardyWagerBeforeFinalJeopardy_returnsError()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        gameSessionService.startGame(
                session.getId(),
                session.getPlayers().getFirst().getId()
        );

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        stompSession.send(
                "/app/sessions/" + session.getId() + "/place-final-jeopardy-wager",
                new PlaceFinalJeopardyWagerRequest(0
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                GameErrorCode.INVALID_STATE,
                error.code()
        );
    }

    @Test
    void answeringFinalJeopardy_broadcastsThatThePlayerAnswered()
            throws Exception {

        GameSession session = createSessionInFinalJeopardy();

        session.timeoutFinalJeopardyWagering();

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        stompSession.send(
                "/app/sessions/" + session.getId() + "/answer-final-jeopardy",
                new AnswerFinalJeopardyRequest("Final answer")
        );

        GameSessionResponse response =
                awaitMessage(receivedMessages);

        assertEquals(
                GameSessionState.FINAL_JEOPARDY_ANSWERING,
                response.state()
        );

        assertEquals(
                "Final question",
                response.finalJeopardy().question()
        );

        assertTrue(
                response.finalJeopardy().players().getFirst().hasAnswered()
        );

        assertNull(
                response.finalJeopardy().players().getFirst().answer()
        );
    }

    @Test
    void answeringFinalJeopardyWhileWagering_returnsError()
            throws Exception {

        GameSession session = createSessionInFinalJeopardy();

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        stompSession.send(
                "/app/sessions/" + session.getId() + "/answer-final-jeopardy",
                new AnswerFinalJeopardyRequest("Final answer"
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                GameErrorCode.INVALID_STATE,
                error.code()
        );
    }

    @Test
    void placingFinalJeopardyWagerWithMissingWager_returnsError()
            throws Exception {

        GameSession session = createSessionInFinalJeopardy();

        StompSession stompSession = connect(tokenOf(session));

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        stompSession.send(
                "/app/sessions/" + session.getId() + "/place-final-jeopardy-wager",
                Map.of()
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                GameErrorCode.INVALID_REQUEST,
                error.code()
        );
    }

    // -------------------------------------------------------------------------
    // Host, timers, leaving and kicking
    // -------------------------------------------------------------------------

    @Test
    void onlyTheHostCanStart()
            throws Exception {

        GameSession session = createSessionWithPlayer();
        GameSessionPlayer other =
                gameSessionService.joinSession(session.getId(), "John");

        StompSession stompSession = connect(other.getToken());
        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        startGame(stompSession, session.getId());

        assertEquals(GameErrorCode.NOT_HOST, awaitError(errors).code());
        assertEquals(GameSessionState.WAITING_FOR_START, session.getState());
    }

    @Test
    void hostUpdatingTimers_broadcastsThem()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        StompSession stompSession = connect(tokenOf(session));
        BlockingQueue<GameSessionResponse> messages =
                subscribeToSession(stompSession, session.getId());
        subscribeToErrors(stompSession);

        stompSession.send(
                "/app/sessions/" + session.getId() + "/update-timers",
                Map.of("buzzerMs", 4000)
        );

        GameSessionResponse response = awaitMessage(messages);

        assertEquals(4000, response.timers().buzzerMs());
        assertEquals(30000, response.timers().clueChoiceMs());
    }

    @Test
    void hostBlockingLateJoins_broadcastsIt_andJoiningAfterTheStartFails()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        StompSession stompSession = connect(tokenOf(session));
        BlockingQueue<GameSessionResponse> messages =
                subscribeToSession(stompSession, session.getId());
        subscribeToErrors(stompSession);

        stompSession.send(
                "/app/sessions/" + session.getId() + "/update-settings",
                Map.of("allowLateJoin", false)
        );

        assertFalse(awaitMessage(messages).allowLateJoin());

        startGame(stompSession, session.getId());
        awaitMessage(messages);

        assertThrows(
                SessionNotJoinableException.class,
                () -> gameSessionService.joinSession(session.getId(), "Late")
        );
    }

    @Test
    void timersOutsideTheBounds_areRejected()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        StompSession stompSession = connect(tokenOf(session));
        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        stompSession.send(
                "/app/sessions/" + session.getId() + "/update-timers",
                Map.of("buzzerMs", 10)
        );

        GameSessionErrorResponse error = awaitError(errors);

        assertEquals(GameErrorCode.INVALID_REQUEST, error.code());
        assertEquals("buzzerMs must be between 1000 and 300000", error.message());
    }

    @Test
    void aFieldOfTheWrongType_isRejected()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        StompSession stompSession = connect(tokenOf(session));
        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        stompSession.send(
                "/app/sessions/" + session.getId() + "/choose-clue",
                Map.of("clueId", "abc")
        );

        GameSessionErrorResponse error = awaitError(errors);

        assertEquals(GameErrorCode.INVALID_REQUEST, error.code());
        assertEquals("Invalid request: the body isn't valid JSON for this action", error.message());
    }

    // This client sends an empty Map as an empty body, not as "{}" (which
    // would get "clueId must not be null").
    @Test
    void anEmptyBody_isRejectedWithAMessage()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        StompSession stompSession = connect(tokenOf(session));
        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        stompSession.send(
                "/app/sessions/" + session.getId() + "/choose-clue",
                Map.of()
        );

        GameSessionErrorResponse error = awaitError(errors);

        assertEquals(GameErrorCode.INVALID_REQUEST, error.code());
        assertEquals("Payload value must not be empty", error.message());
    }

    @Test
    void aWagerTooLargeForAnInt_isRejected()
            throws Exception {

        GameSession session = createSessionWaitingForWager();

        StompSession stompSession = connect(tokenOf(session));
        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        stompSession.send(
                "/app/sessions/" + session.getId() + "/place-daily-double-wager",
                Map.of("wager", 99_999_999_999L)
        );

        assertEquals(GameErrorCode.INVALID_REQUEST, awaitError(errors).code());
    }

    @Test
    void aTooLongAnswer_isRejected()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        StompSession stompSession = connect(tokenOf(session));
        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        answer(
                stompSession,
                session.getId(),
                new AnswerClueRequest("x".repeat(201))
        );

        GameSessionErrorResponse error = awaitError(errors);

        assertEquals(GameErrorCode.INVALID_REQUEST, error.code());
        assertEquals("answer must be at most 200 characters", error.message());
    }

    // STOMP has no Accept-Language, so default messages would follow the
    // server's default locale.
    @Test
    void validationMessages_areNotLocalized()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        StompSession stompSession = connect(tokenOf(session));
        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        Locale defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.GERMAN);

        try {
            chooseClue(
                    stompSession,
                    session.getId(),
                    new ChooseClueRequest(null)
            );

            assertEquals("clueId must not be null", awaitError(errors).message());
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    @Test
    void leaving_removesThePlayerAndPassesOnTheHost()
            throws Exception {

        GameSession session = createSessionWithPlayer();
        GameSessionPlayer other =
                gameSessionService.joinSession(session.getId(), "John");

        StompSession host = connect(tokenOf(session));
        StompSession watcher = connect();
        BlockingQueue<GameSessionResponse> messages =
                subscribeToSession(watcher, session.getId());
        // The leave goes through another connection, so it could beat the
        // watcher's SUBSCRIBE (see playerJoiningSession_broadcastsUpdatedSession).
        awaitSubscriptions(watcher, createSession().getId());

        host.send("/app/sessions/" + session.getId() + "/leave", Map.of());

        GameSessionResponse response = awaitMessage(messages);

        assertEquals(List.of(other.getId()), response.players().stream().map(PlayerResponse::id).toList());
        assertEquals(other.getId(), response.hostPlayerId());
        awaitDisconnected(host);
    }

    @Test
    void kickedPlayer_isDisconnectedAndTheirTokenIsRejected()
            throws Exception {

        GameSession session = createSessionWithPlayer();
        GameSessionPlayer other =
                gameSessionService.joinSession(session.getId(), "John");

        StompSession host = connect(tokenOf(session));
        StompSession kicked = connect(other.getToken());
        BlockingQueue<GameSessionResponse> messages =
                subscribeToSession(host, session.getId());
        subscribeToErrors(host);

        host.send(
                "/app/sessions/" + session.getId() + "/kick",
                Map.of("playerId", other.getId())
        );

        GameSessionResponse response = awaitMessage(messages);

        assertEquals(1, response.players().size());
        awaitDisconnected(kicked);
        assertThrows(ExecutionException.class, () -> connect(other.getToken()));
        assertTrue(host.isConnected());
    }

    @Test
    void lastPlayerLeaving_closesTheSession()
            throws Exception {

        GameSession session = createSessionWithPlayer();
        String token = tokenOf(session);

        StompSession player = connect(token);
        StompSession watcher = connect();
        BlockingQueue<GameSessionResponse> messages =
                subscribeToSession(watcher, session.getId());
        // The leave goes through another connection, so it could beat the
        // watcher's SUBSCRIBE (see playerJoiningSession_broadcastsUpdatedSession).
        awaitSubscriptions(watcher, createSession().getId());

        player.send("/app/sessions/" + session.getId() + "/leave", Map.of());

        GameSessionResponse response = awaitMessage(messages);

        assertEquals(GameSessionState.CLOSED, response.state());
        assertEquals(SessionCloseReason.ABANDONED, response.closeReason());
        assertThrows(GameSessionNotFoundException.class, () -> gameSessionService.getSession(session.getId()));
        assertThrows(ExecutionException.class, () -> connect(token));
    }

    // The server closes a connection by sending an ERROR frame.
    // The departing player's own connection is subscribed, so the departure
    // broadcast is being written to it when the ERROR frame is sent: the race
    // that used to drop the frame. Repeated, because it didn't fail every time.
    @Test
    void leavingPlayer_alwaysReceivesTheErrorFrameWithTheReason()
            throws Exception {

        for (int attempt = 0; attempt < 15; attempt++) {
            GameSession session = createSessionWithPlayer();
            gameSessionService.joinSession(session.getId(), "John");

            BlockingQueue<String> errorFrames = new LinkedBlockingQueue<>();
            StompSession leaving = connect(tokenOf(session), errorFrames);
            subscribeToSession(leaving, session.getId());
            subscribeToErrors(leaving);
            awaitSubscriptions(leaving, session.getId());

            leaving.send("/app/sessions/" + session.getId() + "/leave", Map.of());

            assertEquals(
                    "You left the session",
                    errorFrames.poll(5, TimeUnit.SECONDS),
                    "attempt " + attempt
            );
            awaitDisconnected(leaving);
        }
    }

    @Test
    void kickedPlayer_alwaysReceivesTheErrorFrameWithTheReason()
            throws Exception {

        for (int attempt = 0; attempt < 15; attempt++) {
            GameSession session = createSessionWithPlayer();
            GameSessionPlayer other =
                    gameSessionService.joinSession(session.getId(), "John");

            StompSession host = connect(tokenOf(session));
            subscribeToErrors(host);

            BlockingQueue<String> errorFrames = new LinkedBlockingQueue<>();
            StompSession kicked = connect(other.getToken(), errorFrames);
            subscribeToSession(kicked, session.getId());
            awaitSubscriptions(kicked, session.getId());

            host.send(
                    "/app/sessions/" + session.getId() + "/kick",
                    Map.of("playerId", other.getId())
            );

            assertEquals(
                    "You were removed from the session by the host",
                    errorFrames.poll(5, TimeUnit.SECONDS),
                    "attempt " + attempt
            );
            awaitDisconnected(kicked);
        }
    }

    // Connects and records the "message" header of every ERROR frame received.
    private StompSession connect(String playerToken, BlockingQueue<String> errorFrames)
            throws Exception {

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add(PlayerAuthenticationInterceptor.TOKEN_HEADER, playerToken);

        return stompClient
                .connectAsync(
                        "ws://localhost:" + port + "/ws",
                        new WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {
                            @Override
                            public void handleFrame(StompHeaders headers, Object payload) {
                                errorFrames.add(String.valueOf(headers.getFirst("message")));
                            }
                        }
                )
                .get(5, TimeUnit.SECONDS);
    }

    // The outbound channel is a thread pool: without preserved publish order,
    // frames to one client can be written out of order. That reordering is
    // too rare to reproduce reliably, so the setting itself is checked too.
    @Test
    void brokerPreservesPublishOrder() {
        assertTrue(simpleBrokerMessageHandler.isPreservePublishOrder());
    }

    @Test
    void broadcastsReachEachClientInOrder() throws Exception {
        GameSession session = createSessionWithPlayer();
        Long hostId = session.getHostPlayerId();
        StompSession stompSession = connect();
        BlockingQueue<GameSessionResponse> messages =
                subscribeToSession(stompSession, session.getId());
        awaitSubscriptions(stompSession, session.getId());
        messages.clear();

        // Same-sized broadcasts, sent faster than one connection writes them.
        int broadcasts = 100;
        for (int i = 0; i < broadcasts; i++) {
            gameSessionService.updateSettings(session.getId(), hostId, new SessionSettingsRequest(i % 2 == 0));
        }

        long previous = -1;
        for (int i = 0; i < broadcasts; i++) {
            long version = awaitMessage(messages).version();
            assertTrue(version > previous, "version " + version + " arrived after " + previous);
            previous = version;
        }
    }

    // SUBSCRIBE has no reply, so wait until the server has registered the
    // topic subscription: a broadcast must reach this connection.
    private void awaitSubscriptions(StompSession stompSession, SessionCode sessionId)
            throws Exception {

        BlockingQueue<GameSessionResponse> probe =
                subscribeToSession(stompSession, sessionId);

        for (int i = 0; i < 50; i++) {
            gameSessionService.joinSession(sessionId, "Probe");

            if (probe.poll(100, TimeUnit.MILLISECONDS) != null) {
                return;
            }
        }

        throw new AssertionError("The subscription never received a broadcast");
    }

    private void awaitDisconnected(StompSession stompSession) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (stompSession.isConnected() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertFalse(stompSession.isConnected());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private GameSession createSession() {
        return gameSessionService.createSession(
                createBoard()
        );
    }

    /** The secret token of the session's first player. */
    private String tokenOf(GameSession session) {
        return session.getPlayers().getFirst().getToken();
    }

    private GameSession createSessionWithPlayer() {
        GameSession session = createSession();

        gameSessionService.joinSession(
                session.getId(),
                "Nico"
        );

        return session;
    }

    private GameBoard createBoard() {
        return board(
                new BoardClue(10L, 1, "Question", "Correct answer", 200, false),
                new BoardClue(11L, 2, "Question", "Correct answer", 400, false)
        );
    }

    // The Daily Double is worth 1000, the round's highest value, so wagers
    // up to 1000 are valid.
    private GameBoard createBoardWithDailyDouble() {
        return board(
                new BoardClue(10L, 1, "Question", "Correct answer", 200, false),
                new BoardClue(11L, 2, "Question", "Correct answer", 400, false),
                new BoardClue(DAILY_DOUBLE_CLUE_ID, 5, "Daily Double question", "Daily Double answer", 1000, true)
        );
    }

    private GameBoard board(BoardClue... clues) {
        return new GameBoard(List.of(
                new BoardRound(
                        GameRound.JEOPARDY,
                        List.of(new BoardCategory("Category", List.of(clues)))
                )
        ));
    }

    private GameSession createStartedSessionWithDailyDouble() {
        GameSession session = gameSessionService.createSession(
                createBoardWithDailyDouble()
        );

        gameSessionService.joinSession(
                session.getId(),
                "Nico"
        );

        gameSessionService.startGame(
                session.getId(),
                session.getPlayers().getFirst().getId()
        );

        return session;
    }

    private GameSession createSessionWaitingForWager() {
        GameSession session = createStartedSessionWithDailyDouble();

        gameSessionService.chooseClue(
                session.getId(),
                session.getPlayers().getFirst().getId(),
                DAILY_DOUBLE_CLUE_ID
        );

        return session;
    }

    // The player answers the only Jeopardy clue correctly (200 points), which
    // starts Final Jeopardy with its real 30-second wagering timer running.
    private GameSession createSessionInFinalJeopardy() {
        GameSession session = gameSessionService.createSession(
                new GameBoard(List.of(
                        new BoardRound(GameRound.JEOPARDY, List.of(new BoardCategory(
                                "Category",
                                List.of(new BoardClue(10L, 1, "Question", "Correct answer", 200, false))
                        ))),
                        new BoardRound(GameRound.FINAL_JEOPARDY, List.of(new BoardCategory(
                                "Final category",
                                List.of(new BoardClue(30L, 1, "Final question", "Final answer", 2000, false))
                        )))
                ))
        );

        gameSessionService.joinSession(session.getId(), "Nico");

        Long playerId =
                session.getPlayers().getFirst().getId();

        gameSessionService.startGame(session.getId(), playerId);
        gameSessionService.chooseClue(session.getId(), playerId, 10L);
        gameSessionService.finishRevealingClue(session.getId());
        gameSessionService.buzz(session.getId(), playerId);
        gameSessionService.answerClue(session.getId(), playerId, "Correct answer");
        gameSessionService.finishRevealingAnswer(session.getId());

        assertEquals(
                GameSessionState.FINAL_JEOPARDY_WAGERING,
                session.getState()
        );

        return session;
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

    // Browsers send an Origin header on the handshake; the Java client
    // doesn't unless told to, so other tests never hit the origin check.
    private StompSession connectFromOrigin(String origin)
            throws Exception {

        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.setOrigin(origin);

        return stompClient
                .connectAsync(
                        "ws://localhost:" + port + "/ws",
                        handshakeHeaders,
                        new StompHeaders(),
                        new StompSessionHandlerAdapter() {}
                )
                .get(
                        5,
                        TimeUnit.SECONDS
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

    private BlockingQueue<GameSessionResponse>
    subscribeToSession(
            StompSession stompSession,
            SessionCode sessionId
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

    private BlockingQueue<GameSessionErrorResponse>
    subscribeToErrors(
            StompSession stompSession
    ) {
        BlockingQueue<GameSessionErrorResponse> errors =
                new LinkedBlockingQueue<>();

        stompSession.subscribe(
                "/user/queue/errors",
                new StompFrameHandler() {

                    @Override
                    public Type getPayloadType(
                            StompHeaders headers
                    ) {
                        return GameSessionErrorResponse.class;
                    }

                    @Override
                    public void handleFrame(
                            StompHeaders headers,
                            Object payload
                    ) {
                        errors.add(
                                (GameSessionErrorResponse) payload
                        );
                    }
                }
        );

        return errors;
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

    private GameSessionErrorResponse awaitError(
            BlockingQueue<GameSessionErrorResponse> errors
    ) throws InterruptedException {

        GameSessionErrorResponse error =
                errors.poll(
                        5,
                        TimeUnit.SECONDS
                );

        assertNotNull(error);

        return error;
    }

    // Start and buzz need no body: who is acting comes from the connection.
    private void startGame(
            StompSession stompSession,
            SessionCode sessionId
    ) {
        stompSession.send(
                "/app/sessions/" + sessionId + "/start",
                Map.of()
        );
    }

    private void chooseClue(
            StompSession stompSession,
            SessionCode sessionId,
            ChooseClueRequest request
    ) {
        stompSession.send(
                "/app/sessions/" + sessionId + "/choose-clue",
                request
        );
    }

    private void buzz(
            StompSession stompSession,
            SessionCode sessionId
    ) {
        stompSession.send(
                "/app/sessions/" + sessionId + "/buzz",
                Map.of()
        );
    }

    private void answer(
            StompSession stompSession,
            SessionCode sessionId,
            AnswerClueRequest request
    ) {
        stompSession.send(
                "/app/sessions/" + sessionId + "/answer",
                request
        );
    }

    private void placeWager(
            StompSession stompSession,
            SessionCode sessionId,
            PlaceDailyDoubleWagerRequest request
    ) {
        stompSession.send(
                "/app/sessions/" + sessionId + "/place-daily-double-wager",
                request
        );
    }
}
