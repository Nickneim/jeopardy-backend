package com.nickneim.jeopardy.config;

import com.nickneim.jeopardy.game.controller.GameController;
import com.nickneim.jeopardy.game.dto.GameResponse;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.service.GameService;
import com.nickneim.jeopardy.session.controller.GameSessionController;
import com.nickneim.jeopardy.session.lobby.SessionLobby;
import com.nickneim.jeopardy.session.service.GameBoardService;
import com.nickneim.jeopardy.session.service.GameSessionService;
import com.nickneim.jeopardy.session.model.SessionCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Browsers send an Origin header on cross-origin requests (and an OPTIONS
 * "preflight" first for JSON POSTs). These tests send it the way a browser
 * would; other tests don't, which is why they never notice CORS.
 */
@WebMvcTest(controllers = {GameController.class, GameSessionController.class})
class WebCorsConfigTest {

    private static final String FRONTEND = "http://localhost:5173";
    private static final String OTHER_SITE = "https://evil.example";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameService gameService;

    @MockitoBean
    private GameSessionService gameSessionService;

    @MockitoBean
    private GameBoardService gameBoardService;

    @MockitoBean
    private SessionLobby sessionLobby;

    @Test
    void getFromTheFrontend_isAllowed() throws Exception {
        when(gameService.getGameResponse(1L))
                .thenReturn(GameResponse.from(new Game("Show #1", LocalDate.of(2025, 1, 1), "40")));

        mockMvc.perform(get("/api/games/1")
                        .header("Origin", FRONTEND))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND));
    }

    @Test
    void getFromAnotherSite_isRejected() throws Exception {
        mockMvc.perform(get("/api/games/1")
                        .header("Origin", OTHER_SITE))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void joinPreflightFromTheFrontend_isAllowed() throws Exception {
        mockMvc.perform(options("/api/game-sessions/" + SessionCode.random() + "/players")
                        .header("Origin", FRONTEND)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND));
    }

    @Test
    void createSessionPreflightFromAnotherSite_isRejected() throws Exception {
        mockMvc.perform(options("/api/game-sessions")
                        .header("Origin", OTHER_SITE)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
