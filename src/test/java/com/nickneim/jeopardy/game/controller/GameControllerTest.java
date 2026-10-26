package com.nickneim.jeopardy.game.controller;

import com.nickneim.jeopardy.game.dto.GameResponse;
import com.nickneim.jeopardy.game.entity.*;
import com.nickneim.jeopardy.game.exception.GameNotFoundException;
import com.nickneim.jeopardy.game.repository.SeasonGameCount;
import com.nickneim.jeopardy.game.service.GameFilter;
import com.nickneim.jeopardy.game.service.GameService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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

        when(gameService.getGameResponse(123L))
                .thenReturn(GameResponse.from(game));

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

        verify(gameService).getGameResponse(123L);
    }


    @Test
    void getGame_returnsNotFoundWhenGameDoesNotExist() throws Exception {
        when(gameService.getGameResponse(999L))
                .thenThrow(new GameNotFoundException(999L));

        mockMvc.perform(get("/api/games/999"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                    {
                        "status": 404,
                        "message": "Game not found: 999"
                    }
                    """));

        verify(gameService).getGameResponse(999L);
    }

    @Test
    void getGames_returnsGamesForRequestedPage() throws Exception {
        Game game1 = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "40"
        );

        Game game2 = new Game(
                "Show #122",
                LocalDate.of(2025, 1, 1),
                "40"
        );

        ReflectionTestUtils.setField(game1, "id", 123L);
        ReflectionTestUtils.setField(game2, "id", 122L);

        Page<Game> gamePage = new PageImpl<>(
                List.of(game1, game2),
                PageRequest.of(1, 20),
                42
        );

        when(gameService.getGames(1, 20, GameFilter.NONE))
                .thenReturn(gamePage);

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

        verify(gameService).getGames(1, 20, GameFilter.NONE);
    }

    @Test
    void getGames_usesDefaultPageAndSize() throws Exception {
        Page<Game> emptyPage = new PageImpl<>(
                List.of(),
                PageRequest.of(0, 20),
                0
        );

        when(gameService.getGames(0, 20, GameFilter.NONE))
                .thenReturn(emptyPage);

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

        verify(gameService).getGames(0, 20, GameFilter.NONE);
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
        Game game1 = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "39"
        );

        Game game2 = new Game(
                "Show #122",
                LocalDate.of(2025, 1, 1),
                "39"
        );

        ReflectionTestUtils.setField(game1, "id", 123L);
        ReflectionTestUtils.setField(game2, "id", 122L);

        Page<Game> gamePage = new PageImpl<>(
                List.of(game1, game2),
                PageRequest.of(1, 20),
                42
        );

        when(gameService.getGames(1, 20, new GameFilter("39", null, null, null, null)))
                .thenReturn(gamePage);

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

        verify(gameService).getGames(1, 20, new GameFilter("39", null, null, null, null));
    }

    @Test
    void getGames_passesAllFiltersCombined() throws Exception {
        GameFilter filter = new GameFilter(
                "40",
                "show",
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 1, 31),
                true
        );

        when(gameService.getGames(0, 20, filter))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/games")
                        .param("season", "40")
                        .param("title", "show")
                        .param("from", "2025-01-01")
                        .param("to", "2025-01-31")
                        .param("playable", "true"))
                .andExpect(status().isOk());

        verify(gameService).getGames(0, 20, filter);
    }

    @Test
    void getGames_passesPlayableFalse() throws Exception {
        GameFilter filter = new GameFilter(null, null, null, null, false);

        when(gameService.getGames(0, 20, filter))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/games").param("playable", "false"))
                .andExpect(status().isOk());

        verify(gameService).getGames(0, 20, filter);
    }

    @Test
    void getGames_returnsBadRequestForInvalidPlayable() throws Exception {
        mockMvc.perform(get("/api/games").param("playable", "maybe"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                {
                    "status": 400,
                    "message": "Invalid value for 'playable': expected true or false"
                }
                """, JsonCompareMode.STRICT));

        verifyNoInteractions(gameService);
    }

    @Test
    void getGames_includesEachGamesClueCount() throws Exception {
        Game withClues = new Game("Show #123", LocalDate.of(2025, 1, 2), "40");
        Game withoutClues = new Game("Masters #71", LocalDate.of(2025, 1, 1), "jm");
        ReflectionTestUtils.setField(withClues, "id", 123L);
        ReflectionTestUtils.setField(withoutClues, "id", 725L);

        List<Game> games = List.of(withClues, withoutClues);

        when(gameService.getGames(0, 20, GameFilter.NONE))
                .thenReturn(new PageImpl<>(games, PageRequest.of(0, 20), 2));
        when(gameService.countClues(games))
                .thenReturn(Map.of(123L, 61L, 725L, 0L));

        mockMvc.perform(get("/api/games"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                {
                    "content": [
                        { "id": 123, "title": "Show #123", "gameDate": "2025-01-02", "season": "40", "clueCount": 61 },
                        { "id": 725, "title": "Masters #71", "gameDate": "2025-01-01", "season": "jm", "clueCount": 0 }
                    ],
                    "page": 0,
                    "size": 20,
                    "totalElements": 2,
                    "totalPages": 1
                }
                """, JsonCompareMode.STRICT));
    }

    @Test
    void getGames_treatsBlankTitleAsNoFilter() throws Exception {
        when(gameService.getGames(0, 20, GameFilter.NONE))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/games")
                        .param("title", "  "))
                .andExpect(status().isOk());

        verify(gameService).getGames(0, 20, GameFilter.NONE);
    }

    @Test
    void getGames_returnsBadRequestForInvalidFromDate() throws Exception {
        mockMvc.perform(get("/api/games")
                        .param("from", "2025-13-01"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                {
                    "status": 400,
                    "message": "Invalid date for 'from': expected YYYY-MM-DD"
                }
                """, JsonCompareMode.STRICT));

        verifyNoInteractions(gameService);
    }

    @Test
    void getGames_returnsBadRequestForNonIsoToDate() throws Exception {
        mockMvc.perform(get("/api/games")
                        .param("to", "01/31/2025"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                {
                    "status": 400,
                    "message": "Invalid date for 'to': expected YYYY-MM-DD"
                }
                """, JsonCompareMode.STRICT));

        verifyNoInteractions(gameService);
    }

    @Test
    void getGames_returnsBadRequestWhenFromIsAfterTo() throws Exception {
        mockMvc.perform(get("/api/games")
                        .param("from", "2025-02-01")
                        .param("to", "2025-01-31"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                {
                    "status": 400,
                    "message": "'from' must not be after 'to'"
                }
                """, JsonCompareMode.STRICT));

        verifyNoInteractions(gameService);
    }

    @Test
    void getGames_rejectsNonNumericPageLikeAnOutOfRangeOne() throws Exception {
        mockMvc.perform(get("/api/games")
                        .param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                {
                    "status": 400,
                    "message": "Invalid request parameters"
                }
                """, JsonCompareMode.STRICT));

        verifyNoInteractions(gameService);
    }

    @Test
    void getGames_rejectsNonNumericSize() throws Exception {
        mockMvc.perform(get("/api/games")
                        .param("size", "1.5"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                {
                    "status": 400,
                    "message": "Invalid request parameters"
                }
                """, JsonCompareMode.STRICT));

        verifyNoInteractions(gameService);
    }

    @Test
    void getGame_leavesNonNumericIdsToSpringDefaults() throws Exception {
        // A malformed path keeps Spring's default 400 (no ApiErrorResponse body).
        mockMvc.perform(get("/api/games/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(""));

        verifyNoInteractions(gameService);
    }

    @Test
    void getSeasons_returnsSeasonsWithGameCounts() throws Exception {
        when(gameService.getSeasons())
                .thenReturn(List.of(
                        new SeasonGameCount("40", 230L, LocalDate.of(2024, 7, 26)),
                        new SeasonGameCount("9", 229L, LocalDate.of(1993, 6, 4)),
                        new SeasonGameCount("jm", 72L, LocalDate.of(2026, 9, 4))
                ));

        mockMvc.perform(get("/api/games/seasons"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                [
                    { "season": "40", "gameCount": 230 },
                    { "season": "9", "gameCount": 229 },
                    { "season": "jm", "gameCount": 72 }
                ]
                """, JsonCompareMode.STRICT));

        verify(gameService).getSeasons();
    }

    @Test
    void getSeasons_returnsEmptyListWhenNoGamesExist() throws Exception {
        when(gameService.getSeasons())
                .thenReturn(List.of());

        mockMvc.perform(get("/api/games/seasons"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]", JsonCompareMode.STRICT));
    }

}



