package com.nickneim.jeopardy.game.controller;

import com.nickneim.jeopardy.game.dto.GameSummaryResponse;
import com.nickneim.jeopardy.game.dto.PageResponse;
import com.nickneim.jeopardy.game.entity.*;
import com.nickneim.jeopardy.game.exception.GameNotFoundException;
import com.nickneim.jeopardy.game.service.GameService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
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
    void getGame_returnsGameResponse() throws Exception {
        Game game = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "40"
        );

        Category jeopardyCategory = new Category(
                "HISTORY",
                (short) 1,
                "History comments",
                game
        );

        Category doubleJeopardyCategory = new Category(
                "SCIENCE",
                (short) 7,
                "Science comments",
                game
        );

        Category finalJeopardyCategory = new Category(
                "FINAL HISTORY",
                (short) 13,
                "Final comments",
                game
        );

        Clue jeopardyClue = new Clue(
                "Who was the first president of the United States?",
                "Who is George Washington?",
                (short) 3,
                ClueType.TEXT,
                null,
                jeopardyCategory
        );

        Clue doubleJeopardyClue = new Clue(
                "This planet is known as the Red Planet.",
                "What is Mars?",
                (short) 4,
                ClueType.TEXT,
                null,
                doubleJeopardyCategory
        );

        Clue finalClue = new Clue(
                "This historical event began in 1775.",
                "What is the American Revolution?",
                (short) 1,
                ClueType.TEXT,
                null,
                finalJeopardyCategory
        );

        jeopardyCategory.getClues().add(jeopardyClue);
        doubleJeopardyCategory.getClues().add(doubleJeopardyClue);
        finalJeopardyCategory.getClues().add(finalClue);

        game.getCategories().add(jeopardyCategory);
        game.getCategories().add(doubleJeopardyCategory);
        game.getCategories().add(finalJeopardyCategory);

        GameDailyDouble dailyDouble = new GameDailyDouble(
                game,
                doubleJeopardyClue
        );

        game.getDailyDoubles().add(dailyDouble);

        ReflectionTestUtils.setField(game, "id", 123L);
        ReflectionTestUtils.setField(jeopardyCategory, "id", 456L);
        ReflectionTestUtils.setField(doubleJeopardyCategory, "id", 457L);
        ReflectionTestUtils.setField(finalJeopardyCategory, "id", 458L);

        ReflectionTestUtils.setField(jeopardyClue, "id", 1001L);
        ReflectionTestUtils.setField(doubleJeopardyClue, "id", 1002L);
        ReflectionTestUtils.setField(finalClue, "id", 1003L);

        when(gameService.getGame(123L))
                .thenReturn(game);

        mockMvc.perform(get("/api/games/123"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                    {
                        "id": 123,
                        "title": "Show #123",
                        "gameDate": "2025-01-01",
                        "season": "40",
                        "jeopardyRound": [
                            {
                                "id": 456,
                                "name": "HISTORY",
                                "columnNumber": 1,
                                "comments": "History comments",
                                "clues": [
                                    {
                                        "id": 1001,
                                        "question": "Who was the first president of the United States?",
                                        "answer": "Who is George Washington?",
                                        "rowNumber": 3,
                                        "clueType": "TEXT",
                                        "mediaUrl": null
                                    }
                                ]
                            }
                        ],
                        "doubleJeopardyRound": [
                            {
                                "id": 457,
                                "name": "SCIENCE",
                                "columnNumber": 7,
                                "comments": "Science comments",
                                "clues": [
                                    {
                                        "id": 1002,
                                        "question": "This planet is known as the Red Planet.",
                                        "answer": "What is Mars?",
                                        "rowNumber": 4,
                                        "clueType": "TEXT",
                                        "mediaUrl": null
                                    }
                                ]
                            }
                        ],
                        "finalJeopardy": {
                            "id": 458,
                            "name": "FINAL HISTORY",
                            "columnNumber": 13,
                            "comments": "Final comments",
                            "clues": [
                                {
                                    "id": 1003,
                                    "question": "This historical event began in 1775.",
                                    "answer": "What is the American Revolution?",
                                    "rowNumber": 1,
                                    "clueType": "TEXT",
                                    "mediaUrl": null
                                }
                            ]
                        },
                        "dailyDoubleClueIds": [1002]
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



