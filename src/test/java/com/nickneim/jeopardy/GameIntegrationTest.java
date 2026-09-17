package com.nickneim.jeopardy;

import com.nickneim.jeopardy.dto.GameResponse;
import com.nickneim.jeopardy.dto.PageResponse;
import com.nickneim.jeopardy.dto.GameSummaryResponse;
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

    @Test
    void getGame_returnsNotFoundWhenGameDoesNotExist() {
        restTestClient.get()
                .uri("/api/games/999")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .json("""
                    {
                        "status": 404,
                        "message": "Game not found: 999"
                    }
                    """);
    }

    @Test
    void getGames_returnsRequestedPage() {
        restTestClient.get()
                .uri("/api/games?page=1&size=20")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<
                        PageResponse<GameSummaryResponse>>() {})
                .value(body -> {
                    assertThat(body.page()).isEqualTo(1);
                    assertThat(body.size()).isEqualTo(20);
                    assertThat(body.totalElements()).isEqualTo(25);
                    assertThat(body.totalPages()).isEqualTo(2);

                    assertThat(body.content()).hasSize(5);

                    // The newest games should appear first.
                    assertThat(body.content().get(0).title())
                            .isEqualTo("Game Integration Test Game 05");
                    assertThat(body.content().get(1).title())
                            .isEqualTo("Game Integration Test Game 04");
                    assertThat(body.content().get(2).title())
                            .isEqualTo("Game Integration Test Game 03");
                    assertThat(body.content().get(3).title())
                            .isEqualTo("Game Integration Test Game 02");
                    assertThat(body.content().get(4).title())
                            .isEqualTo("Game Integration Test Game");
                });
    }

    @Test
    void getGames_returnsEmptyPageWhenPageIsBeyondAvailableGames() {
        restTestClient.get()
                .uri("/api/games?page=2&size=20")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<
                        PageResponse<GameSummaryResponse>>() {})
                .value(body -> {
                    assertThat(body.page()).isEqualTo(2);
                    assertThat(body.size()).isEqualTo(20);
                    assertThat(body.totalElements()).isEqualTo(25);
                    assertThat(body.totalPages()).isEqualTo(2);
                    assertThat(body.content()).isEmpty();
                });
    }

    @Test
    void getGames_usesDefaultPageAndSize() {
        restTestClient.get()
                .uri("/api/games")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<
                        PageResponse<GameSummaryResponse>>() {})
                .value(body -> {
                    assertThat(body.page()).isEqualTo(0);
                    assertThat(body.size()).isEqualTo(20);
                    assertThat(body.totalElements()).isEqualTo(25);
                    assertThat(body.totalPages()).isEqualTo(2);
                    assertThat(body.content()).hasSize(20);
                });
    }

    @Test
    void getGames_returnsRequestedSeason() {
        restTestClient.get()
                .uri("/api/games?season=39")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<
                        PageResponse<GameSummaryResponse>>() {})
                .value(body -> {
                    assertThat(body.page()).isEqualTo(0);
                    assertThat(body.size()).isEqualTo(20);
                    assertThat(body.totalElements()).isEqualTo(3);
                    assertThat(body.totalPages()).isEqualTo(1);

                    assertThat(body.content()).hasSize(3);

                    assertThat(body.content().get(0).title())
                            .isEqualTo("Game Integration Test Game 25");
                    assertThat(body.content().get(1).title())
                            .isEqualTo("Game Integration Test Game 24");
                    assertThat(body.content().get(2).title())
                            .isEqualTo("Game Integration Test Game 23");
                });
    }
}