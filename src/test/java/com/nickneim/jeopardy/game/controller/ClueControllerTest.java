package com.nickneim.jeopardy.game.controller;

import com.nickneim.jeopardy.game.entity.Category;
import com.nickneim.jeopardy.game.entity.Clue;
import com.nickneim.jeopardy.game.entity.ClueType;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.exception.ClueNotFoundException;
import com.nickneim.jeopardy.game.service.ClueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClueController.class)
class ClueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ClueService clueService;

    @Test
    void getClue_returnsClueResponse() throws Exception {
        Game game = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "40"
        );

        Category category = new Category(
                "HISTORY",
                (short) 1,
                "Some comments",
                game
        );

        Clue clue = new Clue(
                "Who was the first president of the United States?",
                "Who is George Washington?",
                (short) 3,
                ClueType.TEXT,
                null,
                category
        );

        ReflectionTestUtils.setField(game, "id", 789L);
        ReflectionTestUtils.setField(category, "id", 456L);
        ReflectionTestUtils.setField(clue, "id", 123L);

        when(clueService.getClue(123L))
                .thenReturn(clue);

        mockMvc.perform(get("/api/clues/123"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                {
                    "id": 123,
                    "question": "Who was the first president of the United States?",
                    "answer": "Who is George Washington?",
                    "clueType": "TEXT",
                    "mediaUrl": null,
                    "value": 600,
                    "isFinalClue": false,
                    "gameId": 789,
                    "categoryId": 456,
                    "categoryName": "HISTORY",
                    "categoryComments": "Some comments"
                }
                """));

        verify(clueService).getClue(123L);
    }


    @Test
    void getClue_returnsNotFoundWhenClueDoesNotExist() throws Exception {
        when(clueService.getClue(999L))
                .thenThrow(new ClueNotFoundException(999L));

        mockMvc.perform(get("/api/clues/999"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                    {
                        "status": 404,
                        "message": "Clue not found: 999"
                    }
                    """));

        verify(clueService).getClue(999L);
    }
}

