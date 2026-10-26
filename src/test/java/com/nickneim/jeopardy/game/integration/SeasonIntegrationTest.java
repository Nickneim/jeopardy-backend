package com.nickneim.jeopardy.game.integration;

import com.nickneim.jeopardy.TestcontainersConfiguration;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.repository.GameRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.time.LocalDate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
@Sql(
        scripts = "/testdata/season-integration-test-data.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
@Sql(
        scripts = "/testdata/season-integration-test-data-cleanup.sql",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD
)
class SeasonIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    @Autowired
    private GameRepository gameRepository;

    @Test
    void getSeasons_countsSeasonsDifferingOnlyInCaseAsOne() {
        // The season filter matches "JM" and "jm" alike, so the list does too.
        gameRepository.save(new Game("Season Integration Test Game Upper", LocalDate.of(2025, 3, 1), "JM"));

        restTestClient.get()
                .uri("/api/games/seasons")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .json("""
                    [
                        { "season": "40", "gameCount": 3 },
                        { "season": "10", "gameCount": 1 },
                        { "season": "9", "gameCount": 2 },
                        { "season": "jm", "gameCount": 3 },
                        { "season": "pcj", "gameCount": 1 }
                    ]
                    """, JsonCompareMode.STRICT);
    }

    @Test
    void getSeasons_returnsSeasonsWithGameCountsNewestFirst() {
        // Numbered seasons numerically ("9" after "10"), then special seasons
        // by their most recent game.
        restTestClient.get()
                .uri("/api/games/seasons")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .json("""
                    [
                        { "season": "40", "gameCount": 3 },
                        { "season": "10", "gameCount": 1 },
                        { "season": "9", "gameCount": 2 },
                        { "season": "jm", "gameCount": 2 },
                        { "season": "pcj", "gameCount": 1 }
                    ]
                    """, JsonCompareMode.STRICT);
    }
}
