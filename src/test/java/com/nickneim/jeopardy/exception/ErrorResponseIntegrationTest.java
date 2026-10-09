package com.nickneim.jeopardy.exception;

import com.nickneim.jeopardy.game.service.GameFilter;
import com.nickneim.jeopardy.game.service.GameService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Errors that ApiExceptionHandler doesn't handle get Spring's default error
 * body, which must not expose stack traces or exception messages.
 * spring-boot-devtools (only on the classpath when running from the IDE or
 * bootRun, never in tests) turns all of that on through a lowest-precedence
 * property source, so this test adds the same defaults the same way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@ContextConfiguration(initializers = ErrorResponseIntegrationTest.DevToolsErrorDefaults.class)
class ErrorResponseIntegrationTest {

    private static final String INTERNAL_DETAIL = "relation \"secret_table\" does not exist";

    @Autowired
    private RestTestClient restTestClient;

    @MockitoBean
    private GameService gameService;

    @Test
    void unexpectedError_returnsNoStackTraceOrExceptionMessage() {
        when(gameService.getGames(anyInt(), anyInt(), any(GameFilter.class)))
                .thenThrow(new IllegalStateException(INTERNAL_DETAIL));

        String body = restTestClient.get()
                .uri("/api/games")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("\"status\":500");
        assertNoInternals(body);
        assertThat(body).doesNotContain("secret_table");
    }

    @Test
    void malformedPathVariable_returnsNoStackTraceOrExceptionMessage() {
        String body = restTestClient.get()
                .uri("/api/game-sessions/not-a-uuid")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("\"status\":400");
        assertNoInternals(body);
        // "path" echoes the client's own URL, which is fine; the conversion
        // error ("Invalid UUID string: ...", "Failed to convert ...") isn't.
        assertThat(body).doesNotContain("Invalid UUID").doesNotContain("convert");
    }

    private void assertNoInternals(String body) {
        assertThat(body)
                .doesNotContain("\"trace\"")
                .doesNotContain("\"exception\"")
                .doesNotContain("\"errors\"")
                .doesNotContain("java.")
                .doesNotContain("Exception");
    }

    /** What spring-boot-devtools sets, added with the lowest precedence as it does. */
    static class DevToolsErrorDefaults
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment().getPropertySources().addLast(new MapPropertySource(
                    "devtools",
                    Map.of(
                            "spring.web.error.include-stacktrace", "always",
                            "spring.web.error.include-message", "always",
                            "spring.web.error.include-binding-errors", "always"
                    )
            ));
        }
    }
}
