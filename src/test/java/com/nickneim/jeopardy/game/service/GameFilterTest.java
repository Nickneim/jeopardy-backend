package com.nickneim.jeopardy.game.service;

import com.nickneim.jeopardy.game.exception.InvalidGameFilterException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class GameFilterTest {

    @Test
    void blankTitleMeansNoTitleFilter() {
        assertNull(new GameFilter(null, "", null, null).title());
        assertNull(new GameFilter(null, "   ", null, null).title());
    }

    @Test
    void keepsTitleAsGiven() {
        assertEquals(" Show ", new GameFilter(null, " Show ", null, null).title());
    }

    @Test
    void allowsFromEqualToTo() {
        LocalDate date = LocalDate.of(2025, 2, 1);

        GameFilter filter = new GameFilter(null, null, date, date);

        assertEquals(date, filter.from());
        assertEquals(date, filter.to());
    }

    @Test
    void allowsOnlyOneDateBound() {
        assertDoesNotThrow(() -> new GameFilter(null, null, LocalDate.of(2025, 2, 1), null));
        assertDoesNotThrow(() -> new GameFilter(null, null, null, LocalDate.of(2025, 2, 1)));
    }

    @Test
    void rejectsFromAfterTo() {
        InvalidGameFilterException exception = assertThrows(
                InvalidGameFilterException.class,
                () -> new GameFilter(
                        null,
                        null,
                        LocalDate.of(2025, 2, 2),
                        LocalDate.of(2025, 2, 1)
                )
        );

        assertEquals("'from' must not be after 'to'", exception.getMessage());
    }
}
