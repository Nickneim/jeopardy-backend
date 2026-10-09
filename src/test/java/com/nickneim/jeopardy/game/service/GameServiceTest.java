package com.nickneim.jeopardy.game.service;

import com.nickneim.jeopardy.game.entity.Category;
import com.nickneim.jeopardy.game.entity.Clue;
import com.nickneim.jeopardy.game.entity.ClueType;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.entity.GameDailyDouble;
import com.nickneim.jeopardy.game.exception.GameNotFoundException;
import com.nickneim.jeopardy.game.repository.GameClueCount;
import com.nickneim.jeopardy.game.repository.GameRepository;
import com.nickneim.jeopardy.game.repository.SeasonGameCount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameServiceTest {

    @Mock
    private GameRepository gameRepository;

    @InjectMocks
    private GameService gameService;

    @Test
    void getGame_returnsGame() {
        Game game = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "40"
        );

        Category jeopardyCategory = new Category(
                "HISTORY",
                (short) 1,
                "History comments",
                game
        );

        Category doubleJeopardyCategory = new Category(
                "SCIENCE",
                (short) 7,
                "Science comments",
                game
        );

        Category finalJeopardyCategory = new Category(
                "FINAL HISTORY",
                (short) 13,
                "Final comments",
                game
        );

        Clue jeopardyClue = new Clue(
                "Who was the first president of the United States?",
                "Who is George Washington?",
                (short) 3,
                ClueType.TEXT,
                null,
                jeopardyCategory
        );

        Clue doubleJeopardyClue = new Clue(
                "This planet is known as the Red Planet.",
                "What is Mars?",
                (short) 4,
                ClueType.TEXT,
                null,
                doubleJeopardyCategory
        );

        Clue finalClue = new Clue(
                "This historical event began in 1775.",
                "What is the American Revolution?",
                (short) 1,
                ClueType.TEXT,
                null,
                finalJeopardyCategory
        );

        jeopardyCategory.getClues().add(jeopardyClue);
        doubleJeopardyCategory.getClues().add(doubleJeopardyClue);
        finalJeopardyCategory.getClues().add(finalClue);

        game.getCategories().add(jeopardyCategory);
        game.getCategories().add(doubleJeopardyCategory);
        game.getCategories().add(finalJeopardyCategory);

        GameDailyDouble dailyDouble = new GameDailyDouble(
                game,
                doubleJeopardyClue
        );

        game.getDailyDoubles().add(dailyDouble);

        when(gameRepository.findById(123L))
                .thenReturn(Optional.of(game));

        Game returnedGame = gameService.getGame(123L);

        assertEquals(game, returnedGame);

        verify(gameRepository).findById(123L);
    }

    @Test
    void getGame_throwsWhenGameDoesNotExist() {
        when(gameRepository.findById(123L))
                .thenReturn(Optional.empty());

        assertThrows(
                GameNotFoundException.class,
                () -> gameService.getGame(123L)
        );

        verify(gameRepository).findById(123L);
    }

    @Test
    void getGames_returnsGames() {
        Game game1 = new Game(
                "Show #100",
                LocalDate.of(2025, 2, 1),
                "40"
        );

        Game game2 = new Game(
                "Show #101",
                LocalDate.of(2025, 2, 2),
                "40"
        );

        Page<Game> gamePage = new PageImpl<>(
                List.of(game2, game1),
                Pageable.ofSize(20),
                2
        );

        when(gameRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(gamePage);

        Page<Game> returnedPage =
                gameService.getGames(0, 20, GameFilter.NONE);

        assertEquals(gamePage, returnedPage);

        assertEquals(2, returnedPage.getContent().size());
        assertEquals(game2, returnedPage.getContent().get(0));
        assertEquals(game1, returnedPage.getContent().get(1));

        verify(gameRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void getGames_returnsEmptyPageWhenNoGamesExist() {
        Page<Game> emptyPage = new PageImpl<>(
                List.of(),
                Pageable.ofSize(20),
                0
        );

        when(gameRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(emptyPage);

        Page<Game> returnedPage =
                gameService.getGames(0, 20, GameFilter.NONE);

        assertTrue(returnedPage.isEmpty());
        assertEquals(0, returnedPage.getNumber());
        assertEquals(20, returnedPage.getSize());
        assertEquals(0, returnedPage.getTotalElements());
        assertEquals(0, returnedPage.getTotalPages());

        verify(gameRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void getGames_requestsCorrectPageAndSize() {
        Page<Game> gamePage = new PageImpl<>(
                List.of(),
                Pageable.ofSize(10).withPage(2),
                25
        );

        when(gameRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(gamePage);

        gameService.getGames(2, 10, GameFilter.NONE);

        verify(gameRepository).findAll(
                any(Specification.class),
                argThat((Pageable pageable) ->
                        pageable.getPageNumber() == 2
                                && pageable.getPageSize() == 10
                )
        );
    }

    @Test
    void getGames_sortsByGameDateDescendingThenIdDescending() {
        Page<Game> gamePage = new PageImpl<>(
                List.of(),
                Pageable.ofSize(20),
                0
        );

        when(gameRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(gamePage);

        gameService.getGames(0, 20, GameFilter.NONE);

        verify(gameRepository).findAll(
                any(Specification.class),
                argThat((Pageable pageable) -> {
                    var orders = pageable.getSort().toList();

                    return orders.size() == 2
                            && orders.get(0).getProperty().equals("gameDate")
                            && orders.get(0).isDescending()
                            && orders.get(1).getProperty().equals("id")
                            && orders.get(1).isDescending();
                })
        );
    }

    // Which games each filter matches is checked against the database in
    // GameIntegrationTest.
    @Test
    void getGames_returnsFilteredGames() {
        Game game = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "39"
        );

        Page<Game> gamePage = new PageImpl<>(
                List.of(game),
                Pageable.ofSize(20).withPage(0),
                1
        );

        when(gameRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(gamePage);

        Page<Game> returnedPage = gameService.getGames(
                0,
                20,
                new GameFilter("39", "show", LocalDate.of(2025, 1, 1), null, null)
        );

        assertEquals(1, returnedPage.getContent().size());
        assertEquals(game, returnedPage.getContent().getFirst());
        assertEquals(1, returnedPage.getTotalElements());
    }

    @Test
    void countClues_mapsEveryGameAndDefaultsToZero() {
        Game withClues = new Game("Show #1", LocalDate.of(2025, 1, 1), "40");
        Game withoutClues = new Game("Show #2", LocalDate.of(2025, 1, 2), "40");
        ReflectionTestUtils.setField(withClues, "id", 1L);
        ReflectionTestUtils.setField(withoutClues, "id", 2L);

        when(gameRepository.countCluesByGameIds(Set.of(1L, 2L)))
                .thenReturn(List.of(new GameClueCount(1L, 61L)));

        assertEquals(
                Map.of(1L, 61L, 2L, 0L),
                gameService.countClues(List.of(withClues, withoutClues))
        );
    }

    @Test
    void countClues_skipsTheQueryForAnEmptyPage() {
        assertEquals(Map.of(), gameService.countClues(List.of()));

        verifyNoInteractions(gameRepository);
    }

    @Test
    void getSeasons_ordersNumberedSeasonsNumericallyNewestFirstThenSpecialSeasons() {
        when(gameRepository.findSeasonGameCounts())
                .thenReturn(List.of(
                        new SeasonGameCount("9", 229L, LocalDate.of(1993, 6, 4)),
                        new SeasonGameCount("pcj", 49L, LocalDate.of(2026, 5, 15)),
                        new SeasonGameCount("40", 230L, LocalDate.of(2024, 7, 26)),
                        new SeasonGameCount("jm", 72L, LocalDate.of(2026, 9, 4)),
                        new SeasonGameCount("10", 230L, LocalDate.of(1994, 6, 3)),
                        new SeasonGameCount("espn", 10L, LocalDate.of(2026, 9, 4)),
                        new SeasonGameCount("41", 230L, LocalDate.of(2025, 7, 25))
                ));

        List<String> seasons = gameService.getSeasons()
                .stream()
                .map(SeasonGameCount::season)
                .toList();

        // Special seasons with the same latest game date are ordered by name.
        assertEquals(
                List.of("41", "40", "10", "9", "espn", "jm", "pcj"),
                seasons
        );
    }

    @Test
    void getSeasons_returnsEmptyListWhenNoGamesExist() {
        when(gameRepository.findSeasonGameCounts())
                .thenReturn(List.of());

        assertTrue(gameService.getSeasons().isEmpty());
    }

}
