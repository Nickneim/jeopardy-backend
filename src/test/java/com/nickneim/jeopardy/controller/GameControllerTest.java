package com.nickneim.jeopardy.controller;

import com.nickneim.jeopardy.dto.GameResponse;
import com.nickneim.jeopardy.service.GameService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.verify;
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
}