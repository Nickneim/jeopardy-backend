package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.game.entity.Category;
import com.nickneim.jeopardy.game.entity.Clue;
import com.nickneim.jeopardy.game.entity.ClueType;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.entity.GameDailyDouble;
import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.game.exception.GameNotFoundException;
import com.nickneim.jeopardy.game.service.GameService;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GameBoardServiceTest {

    private GameService gameService;
    private GameBoardService gameBoardService;

    @BeforeEach
    void setUp() {
        gameService = mock(GameService.class);
        gameBoardService = new GameBoardService(gameService);
    }

    @Test
    void loadBoard_copiesRoundsCategoriesAndClues() {
        when(gameService.getGame(123L)).thenReturn(createGame());

        GameBoard board = gameBoardService.loadBoard(123L);

        assertEquals(
                List.of(GameRound.JEOPARDY, GameRound.DOUBLE_JEOPARDY, GameRound.FINAL_JEOPARDY),
                board.getRounds().stream().map(BoardRound::round).toList()
        );

        BoardRound jeopardy = board.getRounds().getFirst();
        assertEquals(
                List.of("HISTORY", "SCIENCE"),
                jeopardy.categories().stream().map(BoardCategory::name).toList()
        );

        assertEquals(
                new BoardClue(101L, "Who was the first president?", "George Washington", 200, false),
                board.getClue(101L)
        );
        assertEquals(Set.of(101L, 102L, 103L), board.getClueIds(GameRound.JEOPARDY));
    }

    @Test
    void loadBoard_usesClueValuesForEachRound() {
        when(gameService.getGame(123L)).thenReturn(createGame());

        GameBoard board = gameBoardService.loadBoard(123L);

        assertEquals(400, board.getClue(102L).value());
        assertEquals(800, board.getClue(201L).value());
        assertEquals(2000, board.getClue(301L).value());
    }

    @Test
    void loadBoard_marksDailyDoubles() {
        when(gameService.getGame(123L)).thenReturn(createGame());

        GameBoard board = gameBoardService.loadBoard(123L);

        assertTrue(board.isDailyDouble(103L));
        assertFalse(board.isDailyDouble(101L));
    }

    @Test
    void loadBoard_throwsWhenGameDoesNotExist() {
        when(gameService.getGame(999L))
                .thenThrow(new GameNotFoundException(999L));

        assertThrows(
                GameNotFoundException.class,
                () -> gameBoardService.loadBoard(999L)
        );
    }

    private Game createGame() {
        Game game = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "40"
        );

        Category history = addCategory(game, "HISTORY", 1);
        Category science = addCategory(game, "SCIENCE", 2);
        Category literature = addCategory(game, "LITERATURE", 7);
        Category finalCategory = addCategory(game, "FINAL HISTORY", 13);

        addClue(history, 101L, "Who was the first president?", "George Washington", 1);
        addClue(science, 102L, "What is the chemical symbol for gold?", "Au", 2);
        Clue dailyDouble =
                addClue(history, 103L, "Who was the second president?", "John Adams", 5);
        addClue(literature, 201L, "He wrote Hamlet.", "William Shakespeare", 2);
        addClue(finalCategory, 301L, "This war began in 1775.", "The American Revolution", 1);

        game.getDailyDoubles().add(new GameDailyDouble(game, dailyDouble));

        return game;
    }

    private Category addCategory(Game game, String name, int column) {
        Category category = new Category(name, (short) column, null, game);
        game.getCategories().add(category);
        return category;
    }

    private Clue addClue(Category category, Long id, String question, String answer, int row) {
        Clue clue = new Clue(question, answer, (short) row, ClueType.TEXT, null, category);
        ReflectionTestUtils.setField(clue, "id", id);
        category.getClues().add(clue);
        return clue;
    }
}
