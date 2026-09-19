package com.nickneim.jeopardy.session;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GameSessionController.class)
class GameSessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameSessionService gameSessionService;

    @Test
    void createSession_returnsCreatedSession() throws Exception {
        UUID id = UUID.randomUUID();
        GameSession session = new GameSession(id);

        when(gameSessionService.createSession())
                .thenReturn(session);

        mockMvc.perform(post("/api/game-sessions"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }
}
