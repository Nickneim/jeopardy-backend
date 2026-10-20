package com.nickneim.jeopardy.exception;

import com.nickneim.jeopardy.TestcontainersConfiguration;
import com.nickneim.jeopardy.game.service.GameFilter;
import com.nickneim.jeopardy.game.service.GameService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/** The "dev" profile is the explicit switch that brings error details back. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class DevProfileErrorResponseIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    @MockitoBean
    private GameService gameService;

    @Test
    void unexpectedError_includesStackTraceAndMessage() {
        when(gameService.getGames(anyInt(), anyInt(), any(GameFilter.class)))
                .thenThrow(new IllegalStateException("something broke"));

        String body = restTestClient.get()
                .uri("/api/games")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body)
                .contains("\"trace\":\"java.lang.IllegalStateException: something broke")
                .contains("\"message\":\"something broke\"");
    }
}
