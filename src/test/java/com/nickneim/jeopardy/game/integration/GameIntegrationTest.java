package com.nickneim.jeopardy.game.integration;

import com.nickneim.jeopardy.TestcontainersConfiguration;
import com.nickneim.jeopardy.game.dto.GameResponse;
import com.nickneim.jeopardy.dto.PageResponse;
import com.nickneim.jeopardy.game.dto.GameSummaryResponse;
import com.nickneim.jeopardy.game.entity.Category;
import com.nickneim.jeopardy.game.entity.Clue;
import com.nickneim.jeopardy.game.entity.ClueType;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.entity.GameDailyDouble;
import com.nickneim.jeopardy.game.repository.GameRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
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

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private GameRepository gameRepository;

    @Test
    void getGame_returnsSavedDailyDoublesInClueIdOrder() {
        Game game = new Game("Game Integration Test Game Saved", LocalDate.of(2025, 3, 1), "40");
        Category category = new Category("SAVED", (short) 1, null, game);
        Clue first = new Clue("First", "Answer", (short) 1, ClueType.TEXT, null, category);
        Clue second = new Clue("Second", "Answer", (short) 2, ClueType.TEXT, null, category);
        category.getClues().addAll(List.of(first, second));
        game.getCategories().add(category);
        // Inserted in reverse, so the table's order isn't the clue id order.
        game.getDailyDoubles().add(new GameDailyDouble(game, second));
        game.getDailyDoubles().add(new GameDailyDouble(game, first));

        Long id = gameRepository.save(game).getId();

        restTestClient.get()
                .uri("/api/games/{id}", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody(GameResponse.class)
                .value(body -> assertThat(body.dailyDoubleClueIds())
                        .containsExactly(first.getId(), second.getId()));
    }

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

                    assertThat(body.doubleJeopardyRound()).hasSize(1);

                    assertThat(body.doubleJeopardyRound().get(0).name())
                            .isEqualTo("LITERATURE");
                    assertThat(body.doubleJeopardyRound().get(0).columnNumber())
                            .isEqualTo((short) 7);

                    assertThat(body.finalJeopardy()).isNotNull();
                    assertThat(body.finalJeopardy().name())
                            .isEqualTo("FINAL HISTORY");
                    assertThat(body.finalJeopardy().clues())
                            .hasSize(1);

                    Long scienceClueId = body.jeopardyRound().get(1)
                            .clues().get(0).id();

                    assertThat(body.dailyDoubleClueIds())
                            .containsExactly(scienceClueId);
                });
    }

    @Test
    void getGame_loadsEveryCategorysCluesInOneQuery() {
        Statistics statistics = entityManagerFactory
                .unwrap(SessionFactory.class)
                .getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        try {
            restTestClient.get()
                    .uri("/api/games/1")
                    .exchange()
                    .expectStatus().isOk();

            // The game, its categories, their clues (one batch for all four
            // categories) and its Daily Doubles.
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
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
                        "code": "GAME_NOT_FOUND",
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

    @Test
    void getGames_filtersByTitleCaseInsensitively() {
        assertThat(titlesOf("/api/games?title=tEsT gAmE 2"))
                .containsExactly(
                        "Game Integration Test Game 25",
                        "Game Integration Test Game 24",
                        "Game Integration Test Game 23",
                        "Game Integration Test Game 22",
                        "Game Integration Test Game 21",
                        "Game Integration Test Game 20"
                );
    }

    @Test
    void getGames_treatsLikeWildcardsInTitleLiterally() {
        // As wildcards, both would match every game.
        assertThat(titlesOf("/api/games?title={title}", "Game%Test")).isEmpty();
        assertThat(titlesOf("/api/games?title={title}", "Game_Integration")).isEmpty();
    }

    @Test
    void getGames_matchesTitleCaseInsensitivelyBeyondAscii() {
        gameRepository.save(new Game(
                "Game Integration Test Game İstanbul",
                LocalDate.of(2025, 3, 1),
                "40"
        ));

        assertThat(titlesOf("/api/games?title={title}", "İSTANBUL"))
                .containsExactly("Game Integration Test Game İstanbul");
    }

    @Test
    void getGames_ignoresBlankSeason() {
        restTestClient.get()
                .uri("/api/games?season=")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<
                        PageResponse<GameSummaryResponse>>() {})
                .value(body -> assertThat(body.totalElements()).isEqualTo(25));
    }

    @Test
    void getGames_ignoresBlankTitle() {
        restTestClient.get()
                .uri("/api/games?title=")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<
                        PageResponse<GameSummaryResponse>>() {})
                .value(body -> assertThat(body.totalElements()).isEqualTo(25));
    }

    @Test
    void getGames_filtersByInclusiveDateRange() {
        // Games 11 and 12 share a date, so the id breaks the tie.
        assertThat(titlesOf("/api/games?from=2025-02-10&to=2025-02-12"))
                .containsExactly(
                        "Game Integration Test Game 13",
                        "Game Integration Test Game 12",
                        "Game Integration Test Game 11",
                        "Game Integration Test Game 10"
                );
    }

    @Test
    void getGames_filtersByOneSidedDateRange() {
        assertThat(titlesOf("/api/games?from=2025-02-23"))
                .containsExactly(
                        "Game Integration Test Game 25",
                        "Game Integration Test Game 24"
                );

        assertThat(titlesOf("/api/games?to=2025-02-02"))
                .containsExactly(
                        "Game Integration Test Game 02",
                        "Game Integration Test Game"
                );
    }

    @Test
    void getGames_combinesSeasonTitleAndDateFilters() {
        assertThat(titlesOf("/api/games?season=40&title=game 2&from=2025-02-20"))
                .containsExactly(
                        "Game Integration Test Game 22",
                        "Game Integration Test Game 21"
                );
    }

    @Test
    void getGames_pagesFilteredGames() {
        restTestClient.get()
                .uri("/api/games?title=game 2&page=1&size=4")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<
                        PageResponse<GameSummaryResponse>>() {})
                .value(body -> {
                    assertThat(body.page()).isEqualTo(1);
                    assertThat(body.size()).isEqualTo(4);
                    assertThat(body.totalElements()).isEqualTo(6);
                    assertThat(body.totalPages()).isEqualTo(2);
                    assertThat(body.content())
                            .extracting(GameSummaryResponse::title)
                            .containsExactly(
                                    "Game Integration Test Game 21",
                                    "Game Integration Test Game 20"
                            );
                });
    }

    @Test
    void getGames_includesEachGamesClueCount() {
        PageResponse<GameSummaryResponse> body = pageOf("/api/games?size=100");

        assertThat(body.content())
                .filteredOn(game -> game.title().equals("Game Integration Test Game"))
                .extracting(GameSummaryResponse::clueCount)
                .containsExactly(4L);
        assertThat(body.content())
                .filteredOn(game -> !game.title().equals("Game Integration Test Game"))
                .extracting(GameSummaryResponse::clueCount)
                .hasSize(24)
                .containsOnly(0L);
    }

    @Test
    void getGames_playableTrue_onlyReturnsGamesWithClues() {
        PageResponse<GameSummaryResponse> body = pageOf("/api/games?playable=true");

        assertThat(body.totalElements()).isEqualTo(1);
        assertThat(body.totalPages()).isEqualTo(1);
        assertThat(body.content())
                .extracting(GameSummaryResponse::title)
                .containsExactly("Game Integration Test Game");
    }

    @Test
    void getGames_playableFalse_onlyReturnsGamesWithoutClues() {
        PageResponse<GameSummaryResponse> body = pageOf("/api/games?playable=false&size=5");

        // Paging counts only the matching games.
        assertThat(body.totalElements()).isEqualTo(24);
        assertThat(body.totalPages()).isEqualTo(5);
        assertThat(body.content())
                .extracting(GameSummaryResponse::title)
                .containsExactly(
                        "Game Integration Test Game 25",
                        "Game Integration Test Game 24",
                        "Game Integration Test Game 23",
                        "Game Integration Test Game 22",
                        "Game Integration Test Game 21"
                );
    }

    @Test
    void getGames_combinesPlayableWithOtherFilters() {
        assertThat(pageOf("/api/games?playable=true&season=40&to=2025-02-01").totalElements())
                .isEqualTo(1);
        assertThat(pageOf("/api/games?playable=true&season=39").totalElements())
                .isZero();
        assertThat(pageOf("/api/games?playable=false&season=39").totalElements())
                .isEqualTo(3);
    }

    @Test
    void getGames_returnsBadRequestForInvalidPlayable() {
        restTestClient.get()
                .uri("/api/games?playable=maybe")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .json("""
                    {
                        "status": 400,
                        "code": "INVALID_REQUEST",
                        "message": "Invalid value for 'playable': expected true or false"
                    }
                    """);
    }

    @Test
    void getGames_returnsBadRequestForInvalidDate() {
        restTestClient.get()
                .uri("/api/games?from=2025-02-30")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .json("""
                    {
                        "status": 400,
                        "code": "INVALID_REQUEST",
                        "message": "Invalid date for 'from': expected YYYY-MM-DD"
                    }
                    """);
    }

    @Test
    void getGames_returnsBadRequestWhenFromIsAfterTo() {
        restTestClient.get()
                .uri("/api/games?from=2025-02-10&to=2025-02-09")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .json("""
                    {
                        "status": 400,
                        "code": "INVALID_REQUEST",
                        "message": "'from' must not be after 'to'"
                    }
                    """);
    }

    private PageResponse<GameSummaryResponse> pageOf(String uri) {
        PageResponse<GameSummaryResponse> body = restTestClient.get()
                .uri(uri)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<
                        PageResponse<GameSummaryResponse>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();

        return body;
    }

    private List<String> titlesOf(String uri, Object... uriVariables) {
        PageResponse<GameSummaryResponse> body = restTestClient.get()
                .uri(uri, uriVariables)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<
                        PageResponse<GameSummaryResponse>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();

        return body.content()
                .stream()
                .map(GameSummaryResponse::title)
                .toList();
    }
}