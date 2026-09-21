package com.nickneim.jeopardy.game.controller;

import com.nickneim.jeopardy.game.dto.GameResponse;
import com.nickneim.jeopardy.game.dto.GameSummaryResponse;
import com.nickneim.jeopardy.game.dto.PageResponse;
import com.nickneim.jeopardy.game.exception.GameNotFoundException;
import com.nickneim.jeopardy.game.service.GameService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GameController.class)
class GameControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameService gameService;

    @Test
    void getGame_returnsGame() throws Exception {
        GameResponse response = new GameResponse(
                123L,
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "40",
                List.of(),
                List.of(),
                null,
                List.of()
        );

        when(gameService.getGame(123L))
                .thenReturn(response);

        mockMvc.perform(get("/api/games/123"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                    {
                        "id": 123,
                        "title": "Show #123",
                        "gameDate": "2025-01-01",
                        "season": "40",
                        "jeopardyRound": [],
                        "doubleJeopardyRound": [],
                        "finalJeopardy": null,
                        "dailyDoubleClueIds": []
                    }
                    """));

        verify(gameService).getGame(123L);
    }

    @Test
    void getGame_returnsNotFoundWhenGameDoesNotExist() throws Exception {
        when(gameService.getGame(999L))
                .thenThrow(new GameNotFoundException(999L));

        mockMvc.perform(get("/api/games/999"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                    {
                        "status": 404,
                        "message": "Game not found: 999"
                    }
                    """));

        verify(gameService).getGame(999L);
    }

    @Test
    void getGames_returnsGamesForRequestedPage() throws Exception {
        PageResponse<GameSummaryResponse> response = new PageResponse<>(
                List.of(
                        new GameSummaryResponse(
                                123L,
                                "Show #123",
                                LocalDate.of(2025, 1, 1),
                                "40"
                        ),
                        new GameSummaryResponse(
                                122L,
                                "Show #122",
                                LocalDate.of(2025, 1, 1),
                                "40"
                        )
                ),
                1,
                20,
                42,
                3
        );

        when(gameService.getGames(1, 20, null))
                .thenReturn(response);

        mockMvc.perform(get("/api/games")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                    {
                        "content": [
                            {
                                "id": 123,
                                "title": "Show #123",
                                "gameDate": "2025-01-01",
                                "season": "40"
                            },
                            {
                                "id": 122,
                                "title": "Show #122",
                                "gameDate": "2025-01-01",
                                "season": "40"
                            }
                        ],
                        "page": 1,
                        "size": 20,
                        "totalElements": 42,
                        "totalPages": 3
                    }
                    """));

        verify(gameService).getGames(1, 20, null);
    }

    @Test
    void getGames_usesDefaultPageAndSize() throws Exception {
        PageResponse<GameSummaryResponse> response = new PageResponse<>(
                List.of(),
                0,
                20,
                0,
                0
        );

        when(gameService.getGames(0, 20, null))
                .thenReturn(response);

        mockMvc.perform(get("/api/games"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                    {
                        "content": [],
                        "page": 0,
                        "size": 20,
                        "totalElements": 0,
                        "totalPages": 0
                    }
                    """));

        verify(gameService).getGames(0, 20, null);
    }

    @Test
    void getGames_returnsBadRequestForNegativePage() throws Exception {
        mockMvc.perform(get("/api/games")
                        .param("page", "-1")
                        .param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                    {
                        "status": 400,
                        "message": "Invalid request parameters"
                    }
                    """));

        verifyNoInteractions(gameService);
    }

    @Test
    void getGames_returnsBadRequestForZeroSize() throws Exception {
        mockMvc.perform(get("/api/games")
                        .param("page", "1")
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                    {
                        "status": 400,
                        "message": "Invalid request parameters"
                    }
                    """));

        verifyNoInteractions(gameService);
    }

    @Test
    void getGames_returnsBadRequestForSizeGreaterThan100() throws Exception {
        mockMvc.perform(get("/api/games")
                        .param("page", "1")
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                    {
                        "status": 400,
                        "message": "Invalid request parameters"
                    }
                    """));

        verifyNoInteractions(gameService);
    }

    @Test
    void getGames_returnsGamesForRequestedSeason() throws Exception {
        PageResponse<GameSummaryResponse> response = new PageResponse<>(
                List.of(
                        new GameSummaryResponse(
                                123L,
                                "Show #123",
                                LocalDate.of(2025, 1, 1),
                                "39"
                        ),
                        new GameSummaryResponse(
                                122L,
                                "Show #122",
                                LocalDate.of(2025, 1, 1),
                                "39"
                        )
                ),
                1,
                20,
                42,
                3
        );

        when(gameService.getGames(1, 20, "39"))
                .thenReturn(response);

        mockMvc.perform(get("/api/games")
                        .param("page", "1")
                        .param("size", "20")
                        .param("season", "39"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                    {
                        "content": [
                            {
                                "id": 123,
                                "title": "Show #123",
                                "gameDate": "2025-01-01",
                                "season": "39"
                            },
                            {
                                "id": 122,
                                "title": "Show #122",
                                "gameDate": "2025-01-01",
                                "season": "39"
                            }
                        ],
                        "page": 1,
                        "size": 20,
                        "totalElements": 42,
                        "totalPages": 3
                    }
                    """));

        verify(gameService).getGames(1, 20, "39");
    }
}