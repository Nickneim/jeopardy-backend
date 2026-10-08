package com.nickneim.jeopardy.game.integration;

import com.nickneim.jeopardy.game.dto.ClueResponse;
import com.nickneim.jeopardy.game.entity.ClueType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@Sql(
        scripts = "/testdata/clue-integration-test-data.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
@Sql(
        scripts = "/testdata/clue-integration-test-data-cleanup.sql",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD
)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClueIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    @Test
    void getClue_returnsClueFromDatabase() {
        restTestClient.get()
                .uri("/api/clues/1")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ClueResponse.class)
                .value(body -> {
                    assertThat(body.id()).isEqualTo(1L);
                    assertThat(body.question())
                            .isEqualTo("Who was the first president of the United States?");
                    assertThat(body.answer())
                            .isEqualTo("Who is George Washington?");
                    assertThat(body.clueType()).isEqualTo(ClueType.TEXT);
                    assertThat(body.mediaUrl()).isNull();
                    assertThat(body.value()).isEqualTo(600);
                    assertThat(body.isFinalClue()).isFalse();
                    assertThat(body.categoryName()).isEqualTo("HISTORY");
                    assertThat(body.categoryComments())
                            .isEqualTo("History integration test category");
                });
    }

}