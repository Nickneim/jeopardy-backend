package com.nickneim.jeopardy.session.integration;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.dto.AnswerClueRequest;
import com.nickneim.jeopardy.session.dto.BuzzRequest;
import com.nickneim.jeopardy.session.dto.ChooseClueRequest;
import com.nickneim.jeopardy.session.dto.GameSessionErrorResponse;
import com.nickneim.jeopardy.session.dto.GameSessionResponse;
import com.nickneim.jeopardy.session.dto.JoinSessionRequest;
import com.nickneim.jeopardy.session.dto.PlaceDailyDoubleWagerRequest;
import com.nickneim.jeopardy.session.dto.StartGameRequest;
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
import java.util.List;
import java.util.Map;
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
                new BoardClue(10L, "Question", "Correct answer", 200, false),
                new BoardClue(11L, "Question", "Correct answer", 400, false)
        );
    }

    // The Daily Double is worth 1000, the round's highest value, so wagers
    // up to 1000 are valid.
    private GameBoard createBoardWithDailyDouble() {
        return board(
                new BoardClue(10L, "Question", "Correct answer", 200, false),
                new BoardClue(11L, "Question", "Correct answer", 400, false),
                new BoardClue(DAILY_DOUBLE_CLUE_ID, "Daily Double question", "Daily Double answer", 1000, true)
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