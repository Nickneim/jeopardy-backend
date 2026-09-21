package com.nickneim.jeopardy.game.service;

import com.nickneim.jeopardy.game.dto.GameResponse;
import com.nickneim.jeopardy.game.dto.GameSummaryResponse;
import com.nickneim.jeopardy.game.dto.PageResponse;
import com.nickneim.jeopardy.game.entity.Category;
import com.nickneim.jeopardy.game.entity.Clue;
import com.nickneim.jeopardy.game.entity.ClueType;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.entity.GameDailyDouble;
import com.nickneim.jeopardy.game.exception.GameNotFoundException;
import com.nickneim.jeopardy.game.repository.GameRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameServiceTest {

    @Mock
    private GameRepository gameRepository;

    @InjectMocks
    private GameService gameService;

    @Test
    void getGame_mapsGameToResponse() {
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

        GameResponse response = gameService.getGame(123L);

        assertEquals(game.getId(), response.id());
        assertEquals(game.getTitle(), response.title());
        assertEquals(game.getGameDate(), response.gameDate());
        assertEquals(game.getSeason(), response.season());

        assertEquals(1, response.jeopardyRound().size());
        assertEquals(1, response.doubleJeopardyRound().size());
        assertNotNull(response.finalJeopardy());

        var jeopardyResponse = response.jeopardyRound().getFirst();
        assertEquals(jeopardyCategory.getId(), jeopardyResponse.id());
        assertEquals(jeopardyCategory.getName(), jeopardyResponse.name());
        assertEquals(
                jeopardyCategory.getComments(),
                jeopardyResponse.comments()
        );
        assertEquals(
                jeopardyCategory.getColumnNumber(),
                jeopardyResponse.columnNumber()
        );

        var jeopardyClueResponse = jeopardyResponse.clues().getFirst();
        assertEquals(
                jeopardyClue.getQuestion(),
                jeopardyClueResponse.question()
        );
        assertEquals(
                jeopardyClue.getAnswer(),
                jeopardyClueResponse.answer()
        );
        assertEquals(
                jeopardyClue.getClueType(),
                jeopardyClueResponse.clueType()
        );
        assertEquals(
                jeopardyClue.getMediaUrl(),
                jeopardyClueResponse.mediaUrl()
        );
        assertEquals(jeopardyClue.getRowNumber(), jeopardyClueResponse.rowNumber());

        var doubleJeopardyResponse =
                response.doubleJeopardyRound().getFirst();

        assertEquals(
                doubleJeopardyCategory.getName(),
                doubleJeopardyResponse.name()
        );

        var doubleJeopardyClueResponse =
                doubleJeopardyResponse.clues().getFirst();

        assertEquals(
                doubleJeopardyClue.getQuestion(),
                doubleJeopardyClueResponse.question()
        );
        assertEquals(doubleJeopardyClue.getRowNumber(), doubleJeopardyClueResponse.rowNumber());

        var finalResponse = response.finalJeopardy();

        assertEquals(
                finalJeopardyCategory.getName(),
                finalResponse.name()
        );
        assertEquals(
                finalJeopardyCategory.getColumnNumber(),
                finalResponse.columnNumber()
        );

        var finalClueResponse = finalResponse.clues().getFirst();

        assertEquals(finalClue.getQuestion(), finalClueResponse.question());
        assertEquals(finalClue.getRowNumber(), finalClueResponse.rowNumber());

        assertEquals(
                1,
                response.dailyDoubleClueIds().size()
        );
        assertEquals(
                doubleJeopardyClue.getId(),
                response.dailyDoubleClueIds().getFirst()
        );

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
    void getGames_returnsPaginatedGameSummaries() {
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

        when(gameRepository.findAll(any(Pageable.class)))
                .thenReturn(gamePage);

        PageResponse<GameSummaryResponse> response =
                gameService.getGames(0, 20, null);

        assertEquals(0, response.page());
        assertEquals(20, response.size());
        assertEquals(2, response.totalElements());
        assertEquals(1, response.totalPages());

        assertEquals(2, response.content().size());

        assertEquals(
                game2.getTitle(),
                response.content().get(0).title()
        );
        assertEquals(
                game2.getGameDate(),
                response.content().get(0).gameDate()
        );
        assertEquals(
                game2.getSeason(),
                response.content().get(0).season()
        );

        assertEquals(
                game1.getTitle(),
                response.content().get(1).title()
        );
    }

    @Test
    void getGames_returnsEmptyPageWhenNoGamesExist() {
        Page<Game> emptyPage = new PageImpl<>(
                List.of(),
                Pageable.ofSize(20),
                0
        );

        when(gameRepository.findAll(any(Pageable.class)))
                .thenReturn(emptyPage);

        PageResponse<GameSummaryResponse> response =
                gameService.getGames(0, 20, null);

        assertTrue(response.content().isEmpty());
        assertEquals(0, response.page());
        assertEquals(20, response.size());
        assertEquals(0, response.totalElements());
        assertEquals(0, response.totalPages());

        verify(gameRepository).findAll(any(Pageable.class));
    }

    @Test
    void getGames_requestsCorrectPageAndSize() {
        Page<Game> gamePage = new PageImpl<>(
                List.of(),
                Pageable.ofSize(10).withPage(2),
                25
        );

        when(gameRepository.findAll(any(Pageable.class)))
                .thenReturn(gamePage);

        gameService.getGames(2, 10, null);

        verify(gameRepository).findAll(
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

        when(gameRepository.findAll(any(Pageable.class)))
                .thenReturn(gamePage);

        gameService.getGames(0, 20, null);

        verify(gameRepository).findAll(
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

    @Test
    void getGames_filtersBySeason() {
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

        when(gameRepository.findBySeason(eq("39"), any(Pageable.class)))
                .thenReturn(gamePage);

        PageResponse<GameSummaryResponse> response =
                gameService.getGames(0, 20, "39");

        assertEquals(1, response.content().size());
        assertEquals("Show #123", response.content().get(0).title());
        assertEquals("39", response.content().get(0).season());
        assertEquals(1, response.totalElements());

        verify(gameRepository).findBySeason(
                eq("39"),
                any(Pageable.class)
        );
    }
}
