package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.game.entity.Category;
import com.nickneim.jeopardy.game.entity.Clue;
import com.nickneim.jeopardy.game.entity.ClueType;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.game.exception.GameNotFoundException;
import com.nickneim.jeopardy.game.service.GameService;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.exception.UnplayableGameException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// The conversion itself is tested in GameBoardTest (GameBoard.from).
class GameBoardServiceTest {

    private GameService gameService;
    private GameBoardService gameBoardService;

    @BeforeEach
    void setUp() {
        gameService = mock(GameService.class);
        gameBoardService = new GameBoardService(gameService);
    }

    @Test
    void loadBoard_returnsTheLoadedGameAsABoard() {
        when(gameService.getGame(123L)).thenReturn(createGame());

        GameBoard board = gameBoardService.loadBoard(123L);

        assertEquals(Set.of(101L), board.getClueIds(GameRound.JEOPARDY));
        assertEquals("George Washington", board.getClue(101L).answer());
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

    @Test
    void loadBoard_rejectsAGameWhoseCategoriesHaveNoClues() {
        Game game = new Game("Masters #71", LocalDate.of(2026, 5, 1), "jm");
        game.getCategories().add(new Category("HISTORY", (short) 1, null, game));
        when(gameService.getGame(725L)).thenReturn(game);

        UnplayableGameException exception = assertThrows(
                UnplayableGameException.class,
                () -> gameBoardService.loadBoard(725L)
        );

        assertEquals("Game 725 can't be played: the board has no clues", exception.getMessage());
    }

    @Test
    void loadBoard_rejectsAGameWithoutCategories() {
        when(gameService.getGame(725L))
                .thenReturn(new Game("Masters #71", LocalDate.of(2026, 5, 1), "jm"));

        assertThrows(
                UnplayableGameException.class,
                () -> gameBoardService.loadBoard(725L)
        );
    }

    @Test
    void loadBoard_rejectsAGameTheBoardRejects() {
        Game game = createGame();
        Category history = game.getCategories().getFirst();
        Clue blank = new Clue("A clue with no answer", "", (short) 2, ClueType.TEXT, null, history);
        ReflectionTestUtils.setField(blank, "id", 102L);
        history.getClues().add(blank);
        when(gameService.getGame(123L)).thenReturn(game);

        UnplayableGameException exception = assertThrows(
                UnplayableGameException.class,
                () -> gameBoardService.loadBoard(123L)
        );

        assertEquals(
                "Game 123 can't be played: clue 102 has no answer a response could match: \"\"",
                exception.getMessage()
        );
    }

    private Game createGame() {
        Game game = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "40"
        );

        Category history = new Category("HISTORY", (short) 1, null, game);
        game.getCategories().add(history);

        Clue clue = new Clue(
                "Who was the first president?",
                "George Washington",
                (short) 1,
                ClueType.TEXT,
                null,
                history
        );
        ReflectionTestUtils.setField(clue, "id", 101L);
        history.getClues().add(clue);

        return game;
    }
}
