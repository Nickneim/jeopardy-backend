package com.nickneim.jeopardy.game.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
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
