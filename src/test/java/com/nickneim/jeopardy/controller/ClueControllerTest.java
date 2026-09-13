package com.nickneim.jeopardy.controller;

import com.nickneim.jeopardy.dto.ClueResponse;
import com.nickneim.jeopardy.entity.ClueType;
import com.nickneim.jeopardy.service.ClueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
    void getClue_returnsClue() throws Exception {
        ClueResponse response = new ClueResponse(
                123L,
                "Who was the first president of the United States?",
                "Who is George Washington?",
                ClueType.TEXT,
                null,
                300,
                false,
                456L,
                "HISTORY",
                "Some comments"
        );

        when(clueService.getClue(123L))
                .thenReturn(response);

        mockMvc.perform(get("/api/clues/123"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                    {
                        "id": 123,
                        "question": "Who was the first president of the United States?",
                        "answer": "Who is George Washington?",
                        "clueType": "TEXT",
                        "mediaUrl": null,
                        "value": 300,
                        "isFinalClue": false,
                        "categoryId": 456,
                        "categoryName": "HISTORY",
                        "categoryComments": "Some comments"
                    }
                    """));

        verify(clueService).getClue(123L);
    }
}