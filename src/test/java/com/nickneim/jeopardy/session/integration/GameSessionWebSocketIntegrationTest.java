package com.nickneim.jeopardy.session.integration;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.dto.AnswerClueRequest;
import com.nickneim.jeopardy.session.dto.AnswerFinalJeopardyRequest;
import com.nickneim.jeopardy.session.dto.BuzzRequest;
import com.nickneim.jeopardy.session.dto.ChooseClueRequest;
import com.nickneim.jeopardy.session.dto.ClueSlotResponse;
import com.nickneim.jeopardy.session.dto.ClueSlotStatus;
import com.nickneim.jeopardy.session.dto.GameSessionErrorResponse;
import com.nickneim.jeopardy.session.dto.GameSessionResponse;
import com.nickneim.jeopardy.session.dto.JoinSessionRequest;
import com.nickneim.jeopardy.session.dto.PlaceDailyDoubleWagerRequest;
import com.nickneim.jeopardy.session.dto.PlaceFinalJeopardyWagerRequest;
import com.nickneim.jeopardy.session.dto.StartGameRequest;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionState;
import com.nickneim.jeopardy.session.security.PlayerAuthenticationInterceptor;
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
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
class GameSessionWebSocketIntegrationTest {

    private static final Long DAILY_DOUBLE_CLUE_ID = 12L;

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
    // Joining
    // -------------------------------------------------------------------------

    @Test
    void playerJoiningSession_broadcastsUpdatedSession()
            throws Exception {

        GameSession session = createSession();

        StompSession stompSession = connect();

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        joinSession(
                stompSession,
                session.getId(),
                new JoinSessionRequest("Nico")
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

    @Test
    void joiningNonexistentSession_returnsError()
            throws Exception {

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        joinSession(
                stompSession,
                UUID.randomUUID(),
                new JoinSessionRequest("Nico")
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "SESSION_NOT_FOUND",
                error.code()
        );
    }

    // -------------------------------------------------------------------------
    // Starting
    // -------------------------------------------------------------------------

    @Test
    void startingGame_broadcastsUpdatedSession()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        StompSession stompSession = connect();

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        startGame(
                stompSession,
                session.getId(),
                new StartGameRequest(
                        session.getPlayers().getFirst().getId()
                )
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

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        startGame(
                stompSession,
                session.getId(),
                new StartGameRequest(
                        session.getPlayers().getFirst().getId()
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "INVALID_STATE",
                error.code()
        );
    }

    @Test
    void startingNonexistentSession_returnsError()
            throws Exception {

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        startGame(
                stompSession,
                UUID.randomUUID(),
                new StartGameRequest(1L)
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "SESSION_NOT_FOUND",
                error.code()
        );
    }

    @Test
    void startingGameWithPlayerNotInSession_returnsError()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        startGame(
                stompSession,
                session.getId(),
                new StartGameRequest(
                        session.getPlayers().getFirst().getId() + 1
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "PLAYER_NOT_IN_SESSION",
                error.code()
        );
    }

    @Test
    void startingGameWithoutPlayerId_returnsError()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        startGame(
                stompSession,
                session.getId(),
                new StartGameRequest(null)
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "INVALID_REQUEST",
                error.code()
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

        Long playerId =
                session.getPlayers().getFirst().getId();

        Long clueId = 10L;

        StompSession stompSession = connect();

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        chooseClue(
                stompSession,
                session.getId(),
                new ChooseClueRequest(
                        playerId,
                        clueId
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
    }

    @Test
    void choosingClueWithWrongPlayer_returnsError()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        gameSessionService.startGame(
                session.getId(),
                session.getPlayers().getFirst().getId()
        );

        Long actualPlayerId =
                session.getPlayers().getFirst().getId();

        Long wrongPlayerId = actualPlayerId + 1;

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        chooseClue(
                stompSession,
                session.getId(),
                new ChooseClueRequest(
                        wrongPlayerId,
                        10L
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "NOT_YOUR_TURN",
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

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        chooseClue(
                stompSession,
                session.getId(),
                new ChooseClueRequest(
                        playerId,
                        10L
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "CLUE_ALREADY_PLAYED",
                error.code()
        );
    }

    @Test
    void choosingClueInWrongState_returnsError()
            throws Exception {

        GameSession session = createSessionWithPlayer();

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        Long playerId =
                session.getPlayers().getFirst().getId();

        chooseClue(
                stompSession,
                session.getId(),
                new ChooseClueRequest(
                        playerId,
                        10L
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "INVALID_STATE",
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

        Long playerId =
                session.getPlayers().getFirst().getId();

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        chooseClue(
                stompSession,
                session.getId(),
                new ChooseClueRequest(
                        playerId,
                        999L
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "CLUE_NOT_FOUND",
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

        StompSession stompSession = connect();

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        buzz(
                stompSession,
                session.getId(),
                new BuzzRequest(playerId)
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

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        Long playerId =
                session.getPlayers().getFirst().getId();

        buzz(
                stompSession,
                session.getId(),
                new BuzzRequest(playerId)
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "INVALID_STATE",
                error.code()
        );
    }

    @Test
    void playerNotInSession_cannotBuzz()
            throws Exception {

        GameSession session =
                createSessionWithPlayer();

        gameSessionService.startGame(
                session.getId(),
                session.getPlayers().getFirst().getId()
        );

        Long actualPlayerId =
                session.getPlayers().getFirst().getId();

        gameSessionService.chooseClue(
                session.getId(),
                actualPlayerId,
                10L
        );

        gameSessionService.finishRevealingClue(
                session.getId()
        );

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        buzz(
                stompSession,
                session.getId(),
                new BuzzRequest(actualPlayerId + 1)
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "PLAYER_NOT_IN_SESSION",
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

        StompSession stompSession = connect();

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        answer(
                stompSession,
                session.getId(),
                new AnswerClueRequest(
                        playerId,
                        "Correct answer"
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

        StompSession stompSession = connect();

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        answer(
                stompSession,
                session.getId(),
                new AnswerClueRequest(
                        playerId,
                        "Wrong answer"
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
    }

    @Test
    void answeringWithWrongPlayer_returnsError()
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

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        answer(
                stompSession,
                session.getId(),
                new AnswerClueRequest(
                        playerId + 1,
                        "Correct answer"
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "NOT_YOUR_TURN",
                error.code()
        );
    }

    @Test
    void answeringInWrongState_returnsError()
            throws Exception {

        GameSession session =
                createSessionWithPlayer();

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        Long playerId =
                session.getPlayers().getFirst().getId();

        answer(
                stompSession,
                session.getId(),
                new AnswerClueRequest(
                        playerId,
                        "Correct answer"
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "INVALID_STATE",
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

        Long playerId =
                session.getPlayers().getFirst().getId();

        StompSession stompSession = connect();

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        chooseClue(
                stompSession,
                session.getId(),
                new ChooseClueRequest(
                        playerId,
                        DAILY_DOUBLE_CLUE_ID
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

        Long playerId =
                session.getPlayers().getFirst().getId();

        StompSession stompSession = connect();

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        placeWager(
                stompSession,
                session.getId(),
                new PlaceDailyDoubleWagerRequest(
                        playerId,
                        500
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

        Long playerId =
                session.getPlayers().getFirst().getId();

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        placeWager(
                stompSession,
                session.getId(),
                new PlaceDailyDoubleWagerRequest(
                        playerId,
                        5000
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "INVALID_WAGER",
                error.code()
        );
    }

    @Test
    void placingWagerWithWrongPlayer_returnsError()
            throws Exception {

        GameSession session = createSessionWaitingForWager();

        Long playerId =
                session.getPlayers().getFirst().getId();

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        placeWager(
                stompSession,
                session.getId(),
                new PlaceDailyDoubleWagerRequest(
                        playerId + 1,
                        500
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "NOT_YOUR_TURN",
                error.code()
        );
    }

    @Test
    void placingWagerInWrongState_returnsError()
            throws Exception {

        GameSession session = createStartedSessionWithDailyDouble();

        Long playerId =
                session.getPlayers().getFirst().getId();

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        placeWager(
                stompSession,
                session.getId(),
                new PlaceDailyDoubleWagerRequest(
                        playerId,
                        500
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "INVALID_STATE",
                error.code()
        );
    }

    @Test
    void placingWagerOnNonexistentSession_returnsError()
            throws Exception {

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        placeWager(
                stompSession,
                UUID.randomUUID(),
                new PlaceDailyDoubleWagerRequest(
                        1L,
                        500
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "SESSION_NOT_FOUND",
                error.code()
        );
    }

    @Test
    void placingWagerWithMissingWager_returnsError()
            throws Exception {

        GameSession session = createSessionWaitingForWager();

        Long playerId =
                session.getPlayers().getFirst().getId();

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        stompSession.send(
                "/app/sessions/" + session.getId() + "/place-daily-double-wager",
                Map.of("playerId", playerId)
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "INVALID_REQUEST",
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

        StompSession stompSession = connect();

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        answer(
                stompSession,
                session.getId(),
                new AnswerClueRequest(
                        playerId,
                        "Daily Double answer"
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

        Long playerId =
                session.getPlayers().getFirst().getId();

        StompSession stompSession = connect();

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        stompSession.send(
                "/app/sessions/" + session.getId() + "/place-final-jeopardy-wager",
                new PlaceFinalJeopardyWagerRequest(playerId, 150)
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
    void placingFinalJeopardyWagerAboveScore_returnsError()
            throws Exception {

        GameSession session = createSessionInFinalJeopardy();

        Long playerId =
                session.getPlayers().getFirst().getId();

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        stompSession.send(
                "/app/sessions/" + session.getId() + "/place-final-jeopardy-wager",
                new PlaceFinalJeopardyWagerRequest(playerId, 201)
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "INVALID_WAGER",
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

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        stompSession.send(
                "/app/sessions/" + session.getId() + "/place-final-jeopardy-wager",
                new PlaceFinalJeopardyWagerRequest(
                        session.getPlayers().getFirst().getId(),
                        0
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "INVALID_STATE",
                error.code()
        );
    }

    @Test
    void answeringFinalJeopardy_broadcastsThatThePlayerAnswered()
            throws Exception {

        GameSession session = createSessionInFinalJeopardy();

        Long playerId =
                session.getPlayers().getFirst().getId();

        session.timeoutFinalJeopardyWagering();

        StompSession stompSession = connect();

        BlockingQueue<GameSessionResponse> receivedMessages =
                subscribeToSession(
                        stompSession,
                        session.getId()
                );

        stompSession.send(
                "/app/sessions/" + session.getId() + "/answer-final-jeopardy",
                new AnswerFinalJeopardyRequest(playerId, "Final answer")
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

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        stompSession.send(
                "/app/sessions/" + session.getId() + "/answer-final-jeopardy",
                new AnswerFinalJeopardyRequest(
                        session.getPlayers().getFirst().getId(),
                        "Final answer"
                )
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "INVALID_STATE",
                error.code()
        );
    }

    @Test
    void placingFinalJeopardyWagerWithMissingWager_returnsError()
            throws Exception {

        GameSession session = createSessionInFinalJeopardy();

        StompSession stompSession = connect();

        BlockingQueue<GameSessionErrorResponse> errors =
                subscribeToErrors(stompSession);

        stompSession.send(
                "/app/sessions/" + session.getId() + "/place-final-jeopardy-wager",
                Map.of("playerId", session.getPlayers().getFirst().getId())
        );

        GameSessionErrorResponse error =
                awaitError(errors);

        assertEquals(
                "INVALID_REQUEST",
                error.code()
        );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private GameSession createSession() {
        return gameSessionService.createSession(
                createBoard()
        );
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

    private void joinSession(
            StompSession stompSession,
            UUID sessionId,
            JoinSessionRequest request
    ) {
        stompSession.send(
                "/app/sessions/" + sessionId + "/join",
                request
        );
    }

    private void startGame(
            StompSession stompSession,
            UUID sessionId,
            StartGameRequest request
    ) {
        stompSession.send(
                "/app/sessions/" + sessionId + "/start",
                request
        );
    }

    private void chooseClue(
            StompSession stompSession,
            UUID sessionId,
            ChooseClueRequest request
    ) {
        stompSession.send(
                "/app/sessions/" + sessionId + "/choose-clue",
                request
        );
    }

    private void buzz(
            StompSession stompSession,
            UUID sessionId,
            BuzzRequest request
    ) {
        stompSession.send(
                "/app/sessions/" + sessionId + "/buzz",
                request
        );
    }

    private void answer(
            StompSession stompSession,
            UUID sessionId,
            AnswerClueRequest request
    ) {
        stompSession.send(
                "/app/sessions/" + sessionId + "/answer",
                request
        );
    }

    private void placeWager(
            StompSession stompSession,
            UUID sessionId,
            PlaceDailyDoubleWagerRequest request
    ) {
        stompSession.send(
                "/app/sessions/" + sessionId + "/place-daily-double-wager",
                request
        );
    }
}