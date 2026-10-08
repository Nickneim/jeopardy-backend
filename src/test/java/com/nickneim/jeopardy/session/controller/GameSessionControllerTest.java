package com.nickneim.jeopardy.session.controller;

import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.exception.GameNotFoundException;
import com.nickneim.jeopardy.game.service.GameService;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.service.GameSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
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
    private GameService gameService;

    @Test
    void createSession_returnsCreatedSession() throws Exception {
        Long gameId = 123L;
        UUID sessionId = UUID.randomUUID();

        Game game = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "40"
        );

        GameSession session = new GameSession(sessionId, game);

        when(gameService.getGameForPlay(gameId))
                .thenReturn(game);

        when(gameSessionService.createSession(game))
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

        verify(gameService).getGameForPlay(gameId);
        verify(gameSessionService).createSession(game);
    }

    @Test
    void createSession_returnsNotFoundWhenGameDoesNotExist()
            throws Exception {

        Long gameId = 999L;

        when(gameService.getGameForPlay(gameId))
                .thenThrow(new GameNotFoundException(gameId));

        mockMvc.perform(post("/api/game-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "gameId": 999
                        }
                        """))
                .andExpect(status().isNotFound());

        verify(gameService).getGameForPlay(gameId);
        verifyNoInteractions(gameSessionService);
    }
}
