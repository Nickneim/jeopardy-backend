package com.nickneim.jeopardy.config;

import com.nickneim.jeopardy.game.controller.GameController;
import com.nickneim.jeopardy.game.service.GameService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The comma-separated form ALLOWED_ORIGINS uses in other environments, e.g.
 * a production domain plus the dev server.
 */
@WebMvcTest(
        controllers = GameController.class,
        properties = "app.cors.allowed-origins=https://jeopardy.example,http://localhost:5173"
)
class WebCorsConfigMultipleOriginsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameService gameService;

    @Test
    void everyConfiguredOrigin_isAllowed() throws Exception {
        for (String origin : new String[]{"https://jeopardy.example", "http://localhost:5173"}) {
            mockMvc.perform(options("/api/games")
                            .header("Origin", origin)
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin));
        }
    }
}
