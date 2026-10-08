package com.nickneim.jeopardy.session.controller;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.game.exception.GameNotFoundException;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.service.GameBoardService;
import com.nickneim.jeopardy.session.service.GameSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                .andExpect(status().isBadRequest());

        verifyNoInteractions(gameSessionService);
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
}
