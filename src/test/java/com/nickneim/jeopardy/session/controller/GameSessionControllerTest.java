package com.nickneim.jeopardy.session.controller;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.game.exception.GameNotFoundException;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.board.InvalidBoardException;
import com.nickneim.jeopardy.dto.PageResponse;
import com.nickneim.jeopardy.session.dto.GameSessionResponse;
import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import com.nickneim.jeopardy.session.exception.GameSessionAlreadyExistsException;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import com.nickneim.jeopardy.session.exception.LateJoinNotAllowedException;
import com.nickneim.jeopardy.session.exception.UnplayableGameException;
import com.nickneim.jeopardy.session.lobby.SessionLobby;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionTimeouts;
import com.nickneim.jeopardy.session.service.GameBoardService;
import com.nickneim.jeopardy.session.service.GameSessionService;
import com.nickneim.jeopardy.session.model.SessionCode;
import com.nickneim.jeopardy.session.security.PlayerTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GameSessionController.class)
class GameSessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameSessionService gameSessionService;

    @MockitoBean
    private GameBoardService gameBoardService;

    @MockitoBean
    private SessionLobby sessionLobby;

    @Test
    void createSession_returnsCreatedSession() throws Exception {
        Long gameId = 123L;
        SessionCode sessionId = SessionCode.random();

        GameBoard board = new GameBoard(List.of(
                new BoardRound(
                        GameRound.JEOPARDY,
                        List.of(new BoardCategory(
                                "HISTORY",
                                List.of(new BoardClue(10L, 1, "Question", "Answer", 200, false))
                        ))
                )
        ));

        GameSession session = new GameSession(sessionId, board);

        when(gameBoardService.loadBoard(gameId))
                .thenReturn(board);

        when(gameSessionService.createSession(sessionId, board))
                .thenReturn(session);

        mockMvc.perform(put("/api/game-sessions/{id}", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "gameId": 123
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(sessionId.toString()));

        verify(gameBoardService).loadBoard(gameId);
        verify(gameSessionService).createSession(sessionId, board);
    }

    @Test
    void createSession_returnsConflictWhenTheIdIsTaken() throws Exception {
        SessionCode sessionId = SessionCode.random();
        GameBoard board = oneClueBoard();

        when(gameBoardService.loadBoard(123L)).thenReturn(board);
        when(gameSessionService.createSession(sessionId, board))
                .thenThrow(new GameSessionAlreadyExistsException(sessionId));

        mockMvc.perform(put("/api/game-sessions/{id}", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        { "gameId": 123 }
                        """))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {
                            "status": 409,
                            "message": "Game session already exists: %s"
                        }
                        """.formatted(sessionId), JsonCompareMode.STRICT));
    }

    @Test
    void createSession_withATakenId_doesNotLoadTheGame() throws Exception {
        SessionCode sessionId = SessionCode.random();

        doThrow(new GameSessionAlreadyExistsException(sessionId))
                .when(gameSessionService).requireUnusedId(sessionId);

        mockMvc.perform(put("/api/game-sessions/{id}", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        { "gameId": 123 }
                        """))
                .andExpect(status().isConflict());

        verifyNoInteractions(gameBoardService);
        verify(gameSessionService, never()).createSession(any(), any());
    }

    @Test
    void createSession_returnsNotFoundWhenGameDoesNotExist()
            throws Exception {

        Long gameId = 999L;

        when(gameBoardService.loadBoard(gameId))
                .thenThrow(new GameNotFoundException(gameId));

        mockMvc.perform(put("/api/game-sessions/{id}", SessionCode.random())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "gameId": 999
                        }
                        """))
                .andExpect(status().isNotFound());

        verify(gameBoardService).loadBoard(gameId);
        verify(gameSessionService, never()).createSession(any(), any());
    }

    @Test
    void createSession_returnsUnprocessableContentWhenGameHasNoClues()
            throws Exception {

        when(gameBoardService.loadBoard(725L))
                .thenThrow(new UnplayableGameException(725L, new InvalidBoardException("the board has no clues")));

        mockMvc.perform(put("/api/game-sessions/{id}", SessionCode.random())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "gameId": 725
                        }
                        """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().json("""
                        {
                            "status": 422,
                            "message": "Game 725 can't be played: the board has no clues"
                        }
                        """, JsonCompareMode.STRICT));

        verify(gameSessionService, never()).createSession(any(), any());
    }

    @Test
    void joinSession_returnsPlayerIdAndToken() throws Exception {
        SessionCode sessionId = SessionCode.random();
        GameSessionPlayer player = new GameSessionPlayer(42L, "Nico", PlayerTokens.generate());

        when(gameSessionService.joinSession(sessionId, "Nico"))
                .thenReturn(player);

        mockMvc.perform(post("/api/game-sessions/" + sessionId + "/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "playerName": "Nico"
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.playerId").value(42))
                .andExpect(jsonPath("$.token").value(player.getToken()));

        verify(gameSessionService).joinSession(sessionId, "Nico");
    }

    @Test
    void joinSession_rejectsBlankName() throws Exception {
        SessionCode sessionId = SessionCode.random();

        mockMvc.perform(post("/api/game-sessions/" + sessionId + "/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "playerName": "   "
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {
                            "status": 400,
                            "message": "playerName must not be blank"
                        }
                        """, JsonCompareMode.STRICT));

        verifyNoInteractions(gameSessionService);
    }

    @Test
    void joinSession_rejectsMissingName() throws Exception {
        SessionCode sessionId = SessionCode.random();

        mockMvc.perform(post("/api/game-sessions/" + sessionId + "/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {
                            "status": 400,
                            "message": "playerName must not be blank"
                        }
                        """, JsonCompareMode.STRICT));

        verifyNoInteractions(gameSessionService);
    }

    @Test
    void joinSession_validationMessageIsNotLocalized() throws Exception {
        SessionCode sessionId = SessionCode.random();

        mockMvc.perform(post("/api/game-sessions/" + sessionId + "/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "es-AR")
                        .content("""
                        { "playerName": "" }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("playerName must not be blank"));
    }

    @Test
    void createSession_rejectsMissingGameId() throws Exception {
        mockMvc.perform(put("/api/game-sessions/{id}", SessionCode.random())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {
                            "status": 400,
                            "message": "gameId must not be null"
                        }
                        """, JsonCompareMode.STRICT));

        verifyNoInteractions(gameBoardService, gameSessionService);
    }

    @Test
    void joinSession_returnsNotFoundWhenSessionDoesNotExist() throws Exception {
        SessionCode sessionId = SessionCode.random();

        when(gameSessionService.joinSession(sessionId, "Nico"))
                .thenThrow(new GameSessionNotFoundException(sessionId));

        mockMvc.perform(post("/api/game-sessions/" + sessionId + "/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "playerName": "Nico"
                        }
                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Game session not found: " + sessionId));
    }

    @Test
    void getSession_returnsTheCurrentState() throws Exception {
        SessionCode sessionId = SessionCode.random();
        GameSession session = new GameSession(sessionId, new GameBoard(List.of(
                new BoardRound(
                        GameRound.JEOPARDY,
                        List.of(new BoardCategory(
                                "HISTORY",
                                List.of(new BoardClue(10L, 1, "Question", "Answer", 200, false))
                        ))
                )
        )));
        session.addPlayer("Nico", PlayerTokens.generate());
        session.incrementVersion();

        when(gameSessionService.getSessionState(sessionId))
                .thenReturn(GameSessionResponse.from(session));

        mockMvc.perform(get("/api/game-sessions/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sessionId.toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.state").value("WAITING_FOR_START"))
                .andExpect(jsonPath("$.players[0].id").value(1))
                .andExpect(jsonPath("$.players[0].name").value("Nico"))
                .andExpect(jsonPath("$.board[0].name").value("HISTORY"));
    }

    // Typed from a link: lowercase, and O read as 0, find the same session.
    @Test
    void getSession_acceptsALowercaseCodeWithAnOForAZero() throws Exception {
        SessionCode sessionId = SessionCode.of("7KQ20");
        when(gameSessionService.getSessionState(sessionId))
                .thenThrow(new GameSessionNotFoundException(sessionId));

        mockMvc.perform(get("/api/game-sessions/7kq2o"))
                .andExpect(status().isNotFound());

        verify(gameSessionService).getSessionState(sessionId);
    }

    @Test
    void getSession_rejectsAMalformedCode() throws Exception {
        for (String code : List.of("ABC", "ABCDEF", "ABCU1", "AB-12")) {
            mockMvc.perform(get("/api/game-sessions/" + code))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().json("""
                            {
                                "status": 400,
                                "message": "Invalid session code: expected 5 letters and digits"
                            }
                            """, JsonCompareMode.STRICT));
        }

        verifyNoInteractions(gameSessionService);
    }

    @Test
    void getSession_includesTheHostTimersAndLeavingFlags() throws Exception {
        SessionCode sessionId = SessionCode.random();
        GameSession session = new GameSession(sessionId, oneClueBoard());
        session.addPlayer("Nico", PlayerTokens.generate());
        session.addPlayer("John", PlayerTokens.generate());

        when(gameSessionService.getSessionState(sessionId))
                .thenReturn(GameSessionResponse.from(session));

        mockMvc.perform(get("/api/game-sessions/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostPlayerId").value(1))
                .andExpect(jsonPath("$.closeReason").isEmpty())
                .andExpect(jsonPath("$.players[1].leaving").value(false))
                .andExpect(jsonPath("$.timers.clueChoiceMs").value(30000))
                .andExpect(jsonPath("$.timers.clueRevealMs").value(3000))
                .andExpect(jsonPath("$.timers.dailyDoubleWagerMs").value(30000))
                .andExpect(jsonPath("$.timers.buzzerMs").value(10000))
                .andExpect(jsonPath("$.timers.answerMs").value(5000))
                .andExpect(jsonPath("$.timers.answerRevealMs").value(3000))
                .andExpect(jsonPath("$.timers.finalJeopardyWagerMs").value(30000))
                .andExpect(jsonPath("$.timers.finalJeopardyAnswerMs").value(30000))
                .andExpect(jsonPath("$.timers.finalJeopardyRevealMs").value(10000));
    }

    @Test
    void joinSession_returnsConflictWhenLateJoiningIsBlocked() throws Exception {
        SessionCode sessionId = SessionCode.random();

        when(gameSessionService.joinSession(sessionId, "Nico"))
                .thenThrow(new LateJoinNotAllowedException());

        mockMvc.perform(post("/api/game-sessions/" + sessionId + "/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        { "playerName": "Nico" }
                        """))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {
                            "status": 409,
                            "message": "This game has already started and doesn't accept new players"
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void getSession_includesTheJoinSettings() throws Exception {
        SessionCode sessionId = SessionCode.random();
        GameSession session = new GameSession(sessionId, oneClueBoard());
        session.addPlayer("Nico", PlayerTokens.generate());
        session.updateLateJoin(1L, false);

        when(gameSessionService.getSessionState(sessionId))
                .thenReturn(GameSessionResponse.from(session));

        mockMvc.perform(get("/api/game-sessions/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowLateJoin").value(false))
                .andExpect(jsonPath("$.joinScore").value(0));
    }

    // Timers are set in the lobby. A client that still sends them on create
    // gets a session with the defaults, not an error.
    @Test
    void createSession_ignoresTimersInTheRequest() throws Exception {
        GameBoard board = oneClueBoard();
        GameSession session = new GameSession(SessionCode.random(), board);

        when(gameBoardService.loadBoard(123L)).thenReturn(board);
        when(gameSessionService.createSession(session.getId(), board)).thenReturn(session);

        mockMvc.perform(put("/api/game-sessions/{id}", session.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "gameId": 123,
                            "timers": { "buzzerMs": 10 }
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(session.getId().toString()));

        verify(gameSessionService).createSession(session.getId(), board);
    }

    // -------------------------------------------------------------------------
    // Listing
    // -------------------------------------------------------------------------

    @Test
    void listSessions_returnsAPageOfSummaries() throws Exception {
        SessionCode sessionId = SessionCode.random();
        GameSession session = new GameSession(
                sessionId,
                new GameBoard(7L, "Show #7", oneClueBoard().getRounds()),
                GameSessionTimeouts.defaults(),
                Instant.parse("2026-10-09T12:30:00Z")
        );
        session.addPlayer("Nico", PlayerTokens.generate());

        when(sessionLobby.listSessions(false, 0, 20)).thenReturn(new PageResponse<>(
                List.of(GameSessionSummaryResponse.from(session)), 0, 20, 1, 1
        ));

        mockMvc.perform(get("/api/game-sessions"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                            "content": [{
                                "id": "%s",
                                "gameId": 7,
                                "gameTitle": "Show #7",
                                "state": "WAITING_FOR_START",
                                "round": "JEOPARDY",
                                "playerCount": 1,
                                "hostName": "Nico",
                                "createdAt": "2026-10-09T12:30:00Z",
                                "joinable": true,
                                "allowLateJoin": true,
                                "joinScore": 0
                            }],
                            "page": 0,
                            "size": 20,
                            "totalElements": 1,
                            "totalPages": 1
                        }
                        """.formatted(sessionId), JsonCompareMode.STRICT));
    }

    @Test
    void listSessions_passesThePagingAndFilter() throws Exception {
        when(sessionLobby.listSessions(true, 2, 5))
                .thenReturn(new PageResponse<>(List.of(), 2, 5, 0, 0));

        mockMvc.perform(get("/api/game-sessions?page=2&size=5&includeFinished=true"))
                .andExpect(status().isOk());

        verify(sessionLobby).listSessions(true, 2, 5);
    }

    @Test
    void listSessions_rejectsInvalidPaging() throws Exception {
        mockMvc.perform(get("/api/game-sessions?size=101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request parameters"));

        mockMvc.perform(get("/api/game-sessions?page=-1"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(sessionLobby);
    }

    @Test
    void listSessions_rejectsANonBooleanFilter() throws Exception {
        mockMvc.perform(get("/api/game-sessions?includeFinished=maybe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'includeFinished': expected true or false"));
    }

    private GameBoard oneClueBoard() {
        return new GameBoard(List.of(
                new BoardRound(
                        GameRound.JEOPARDY,
                        List.of(new BoardCategory(
                                "HISTORY",
                                List.of(new BoardClue(10L, 1, "Question", "Answer", 200, false))
                        ))
                )
        ));
    }

    @Test
    void getSession_returnsNotFoundWhenSessionDoesNotExist() throws Exception {
        SessionCode sessionId = SessionCode.random();

        when(gameSessionService.getSessionState(sessionId))
                .thenThrow(new GameSessionNotFoundException(sessionId));

        mockMvc.perform(get("/api/game-sessions/" + sessionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
