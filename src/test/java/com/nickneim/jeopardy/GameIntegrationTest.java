package com.nickneim.jeopardy;

import com.nickneim.jeopardy.dto.GameResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@Sql(
        scripts = "/testdata/game-integration-test-data.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
@Sql(
        scripts = "/testdata/game-integration-test-data-cleanup.sql",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD
)
class GameIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    @Test
    void getGame_returnsGameFromDatabase() {
        restTestClient.get()
                .uri("/api/games/1")
                .exchange()
                .expectStatus().isOk()
                .expectBody(GameResponse.class)
                .value(body -> {
                    assertThat(body.id()).isEqualTo(1L);
                    assertThat(body.title())
                            .isEqualTo("Game Integration Test Game");
                    assertThat(body.gameDate())
                            .isEqualTo("2025-02-01");
                    assertThat(body.season())
                            .isEqualTo("40");

                    assertThat(body.jeopardyRound()).hasSize(2);

                    assertThat(body.jeopardyRound().get(0).name())
                            .isEqualTo("HISTORY");
                    assertThat(body.jeopardyRound().get(0).clues())
                            .hasSize(1);

                    assertThat(body.jeopardyRound().get(1).name())
                            .isEqualTo("SCIENCE");
                    assertThat(body.jeopardyRound().get(1).clues())
                            .hasSize(1);

                    assertThat(body.doubleJeopardyRound())
                            .isEmpty();

                    assertThat(body.finalJeopardy())
                            .isNull();

                    assertThat(body.dailyDoubleClueIds())
                            .isEmpty();
                });
    }
}