package com.nickneim.jeopardy.session.controller;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.game.exception.GameNotFoundException;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.game.dto.PageResponse;
import com.nickneim.jeopardy.session.dto.GameSessionResponse;
import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import com.nickneim.jeopardy.session.exception.LateJoinNotAllowedException;
import com.nickneim.jeopardy.session.exception.UnplayableGameException;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionTimeouts;
import com.nickneim.jeopardy.session.service.GameBoardService;
import com.nickneim.jeopardy.session.service.GameSessionService;
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
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Test
    void createSession_returnsCreatedSession() throws Exception {
        Long gameId = 123L;
        UUID sessionId = UUID.randomUUID();

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

        when(gameSessionService.createSession(board))
                .thenReturn(session);

        mockMvc.perform(post("/api/game-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "gameId": 123
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(sessionId.toString()));

        verify(gameBoardService).loadBoard(gameId);
        verify(gameSessionService).createSession(board);
    }

    @Test
    void createSession_returnsNotFoundWhenGameDoesNotExist()
            throws Exception {

        Long gameId = 999L;

        when(gameBoardService.loadBoard(gameId))
                .thenThrow(new GameNotFoundException(gameId));

        mockMvc.perform(post("/api/game-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "gameId": 999
                        }
                        """))
                .andExpect(status().isNotFound());

        verify(gameBoardService).loadBoard(gameId);
        verifyNoInteractions(gameSessionService);
    }

    @Test
    void createSession_returnsUnprocessableContentWhenGameHasNoClues()
            throws Exception {

        when(gameBoardService.loadBoard(725L))
                .thenThrow(new UnplayableGameException(725L));

        mockMvc.perform(post("/api/game-sessions")
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
                            "message": "Game 725 has no clues and can't be played"
                        }
                        """, JsonCompareMode.STRICT));

        verifyNoInteractions(gameSessionService);
    }

    @Test
    void joinSession_returnsPlayerIdAndToken() throws Exception {
        UUID sessionId = UUID.randomUUID();
        GameSessionPlayer player = new GameSessionPlayer(42L, "Nico");

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
        UUID sessionId = UUID.randomUUID();

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
        UUID sessionId = UUID.randomUUID();

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
        UUID sessionId = UUID.randomUUID();

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
        mockMvc.perform(post("/api/game-sessions")
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
        UUID sessionId = UUID.randomUUID();

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
        UUID sessionId = UUID.randomUUID();
        GameSession session = new GameSession(sessionId, new GameBoard(List.of(
                new BoardRound(
                        GameRound.JEOPARDY,
                        List.of(new BoardCategory(
                                "HISTORY",
                                List.of(new BoardClue(10L, 1, "Question", "Answer", 200, false))
                        ))
                )
        )));
        session.addPlayer("Nico");
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

    @Test
    void getSession_includesTheHostTimersAndLeavingFlags() throws Exception {
        UUID sessionId = UUID.randomUUID();
        GameSession session = new GameSession(sessionId, oneClueBoard());
        session.addPlayer("Nico");
        session.addPlayer("John");

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
        UUID sessionId = UUID.randomUUID();

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
        UUID sessionId = UUID.randomUUID();
        GameSession session = new GameSession(sessionId, oneClueBoard());
        session.addPlayer("Nico");
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
        GameSession session = new GameSession(UUID.randomUUID(), board);

        when(gameBoardService.loadBoard(123L)).thenReturn(board);
        when(gameSessionService.createSession(board)).thenReturn(session);

        mockMvc.perform(post("/api/game-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "gameId": 123,
                            "timers": { "buzzerMs": 10 }
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(session.getId().toString()));

        verify(gameSessionService).createSession(board);
    }

    // -------------------------------------------------------------------------
    // Listing
    // -------------------------------------------------------------------------

    @Test
    void listSessions_returnsAPageOfSummaries() throws Exception {
        UUID sessionId = UUID.randomUUID();
        GameSession session = new GameSession(
                sessionId,
                new GameBoard(7L, "Show #7", oneClueBoard().getRounds()),
                GameSessionTimeouts.defaults(),
                Instant.parse("2026-10-09T12:30:00Z")
        );
        session.addPlayer("Nico");

        when(gameSessionService.listSessions(false, 0, 20)).thenReturn(new PageResponse<>(
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
        when(gameSessionService.listSessions(true, 2, 5))
                .thenReturn(new PageResponse<>(List.of(), 2, 5, 0, 0));

        mockMvc.perform(get("/api/game-sessions?page=2&size=5&includeFinished=true"))
                .andExpect(status().isOk());

        verify(gameSessionService).listSessions(true, 2, 5);
    }

    @Test
    void listSessions_rejectsInvalidPaging() throws Exception {
        mockMvc.perform(get("/api/game-sessions?size=101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request parameters"));

        mockMvc.perform(get("/api/game-sessions?page=-1"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(gameSessionService);
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
        UUID sessionId = UUID.randomUUID();

        when(gameSessionService.getSessionState(sessionId))
                .thenThrow(new GameSessionNotFoundException(sessionId));

        mockMvc.perform(get("/api/game-sessions/" + sessionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
