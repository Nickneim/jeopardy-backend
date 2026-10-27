package com.nickneim.jeopardy.game.integration;

import com.nickneim.jeopardy.TestcontainersConfiguration;
import com.nickneim.jeopardy.dto.PageResponse;
import com.nickneim.jeopardy.game.dto.GameSummaryResponse;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.repository.GameRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The {@code sort} and {@code direction} of GET /api/games, in the database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
@Sql(
        scripts = "/testdata/game-sort-integration-test-data.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
@Sql(
        scripts = "/testdata/game-sort-integration-test-data-cleanup.sql",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD
)
class GameSortIntegrationTest {

    private static final String GAMES = "/api/games?title=Game Sort Test";

    @Autowired
    private RestTestClient restTestClient;

    @Autowired
    private GameRepository gameRepository;

    // Each row: the query, then the games' last letters in the expected order.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // Newest first, ties by id descending (D is 4, c is 3).
            "''                                | e b D c A",
            "&sort=gameDate                    | e b D c A",
            "&sort=gameDate&direction=asc      | A c D b e",
            // Case-insensitive.
            "&sort=title                       | A b c D e",
            "&sort=title&direction=desc        | e D c b A",
            // Numbered seasons numerically, specials after them by name.
            "&sort=season                      | A b D c e",
            "&sort=season&direction=desc       | D b A e c",
            // Ties by id: A (2) before e (5), b (1) before D (4).
            "&sort=clueCount                   | A e c b D",
            "&sort=clueCount&direction=DESC    | D b c e A",
    })
    void sortsEveryMatchingGame(String query, String expected) {
        assertThat(lettersOf(pageOf(GAMES + query.strip() + "&size=100")))
                .containsExactlyElementsOf(Arrays.asList(expected.strip().split(" ")));
    }

    // The database's default collation ignored the space and put #100 first.
    // The lobby sorts the same titles the same way (SessionLobbyTest).
    @Test
    void sortsTitlesByCodePoint_likeTheLobby() {
        gameRepository.save(new Game("Game Sort Test #100 - Tuesday", LocalDate.of(2024, 2, 2), "1"));
        gameRepository.save(new Game("Game Sort Test #10 - Monday", LocalDate.of(2024, 2, 1), "1"));

        assertThat(lettersOf(pageOf("/api/games?title={title}&sort=title", "Game Sort Test #")))
                .containsExactly("#10 - Monday", "#100 - Tuesday");
    }

    @Test
    void sortsBeforePaging() {
        PageResponse<GameSummaryResponse> page = pageOf(GAMES + "&sort=clueCount&direction=desc&page=1&size=2");

        assertThat(lettersOf(page)).containsExactly("c", "e");
        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void combinesTheSortWithFilters() {
        assertThat(lettersOf(pageOf(GAMES + "&playable=true&sort=clueCount")))
                .containsExactly("c", "b", "D");
    }

    @Test
    void rejectsAnUnknownSort() {
        restTestClient.get()
                .uri("/api/games?sort=airDate")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .json("""
                    {
                        "status": 400,
                        "code": "INVALID_REQUEST",
                        "message": "Invalid value for 'sort': expected one of title, season, gameDate, clueCount"
                    }
                    """);
    }

    @Test
    void rejectsAnUnknownDirection() {
        restTestClient.get()
                .uri("/api/games?sort=title&direction=up")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .json("""
                    {
                        "status": 400,
                        "code": "INVALID_REQUEST",
                        "message": "Invalid value for 'direction': expected one of asc, desc"
                    }
                    """);
    }

    private PageResponse<GameSummaryResponse> pageOf(String uri, Object... uriVariables) {
        PageResponse<GameSummaryResponse> body = restTestClient.get()
                .uri(uri, uriVariables)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<PageResponse<GameSummaryResponse>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();

        return body;
    }

    private static List<String> lettersOf(PageResponse<GameSummaryResponse> page) {
        return page.content()
                .stream()
                .map(game -> game.title().substring("Game Sort Test ".length()))
                .toList();
    }
}
