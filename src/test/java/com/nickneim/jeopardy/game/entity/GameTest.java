package com.nickneim.jeopardy.game.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class GameTest {

    private Game game;
    private Clue normalClue;
    private Clue dailyDoubleClue;

    @BeforeEach
    void setUp() {
        game = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "40"
        );

        Category category = new Category(
                "HISTORY",
                (short) 1,
                null,
                game
        );

        normalClue = new Clue(
                "Who was the first president of the United States?",
                "George Washington",
                (short) 1,
                ClueType.TEXT,
                null,
                category
        );

        dailyDoubleClue = new Clue(
                "Who was the second president of the United States?",
                "John Adams",
                (short) 2,
                ClueType.TEXT,
                null,
                category
        );

        ReflectionTestUtils.setField(normalClue, "id", 101L);
        ReflectionTestUtils.setField(dailyDoubleClue, "id", 102L);

        category.getClues().add(normalClue);
        category.getClues().add(dailyDoubleClue);
        game.getCategories().add(category);
        game.getDailyDoubles().add(new GameDailyDouble(game, dailyDoubleClue));
    }

    @Test
    void isDailyDouble_returnsTrueForDailyDoubleClue() {
        assertTrue(game.isDailyDouble(dailyDoubleClue.getId()));
    }

    @Test
    void isDailyDouble_returnsFalseForNormalClue() {
        assertFalse(game.isDailyDouble(normalClue.getId()));
    }

    @Test
    void isDailyDouble_returnsFalseForUnknownClue() {
        assertFalse(game.isDailyDouble(999L));
    }

    @Test
    void newDailyDouble_hasTheGameAndClueIds() {
        ReflectionTestUtils.setField(game, "id", 7L);

        GameDailyDouble dailyDouble = new GameDailyDouble(game, dailyDoubleClue);

        assertEquals(new GameDailyDoubleId(7L, 102L), dailyDouble.getId());
    }
}
