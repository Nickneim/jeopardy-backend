package com.nickneim.jeopardy.service;

import com.nickneim.jeopardy.dto.GameResponse;
import com.nickneim.jeopardy.entity.Category;
import com.nickneim.jeopardy.entity.Clue;
import com.nickneim.jeopardy.entity.ClueType;
import com.nickneim.jeopardy.entity.Game;
import com.nickneim.jeopardy.entity.GameDailyDouble;
import com.nickneim.jeopardy.repository.GameRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
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
        assertEquals(jeopardyClue.getValue(), jeopardyClueResponse.value());

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
        assertEquals(doubleJeopardyClue.getValue(), doubleJeopardyClueResponse.value());

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
        assertEquals(finalClue.getValue(), finalClueResponse.value());

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
                RuntimeException.class,
                () -> gameService.getGame(123L)
        );

        verify(gameRepository).findById(123L);
    }
}