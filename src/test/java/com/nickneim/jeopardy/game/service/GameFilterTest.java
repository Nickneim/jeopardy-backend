package com.nickneim.jeopardy.game.service;

import com.nickneim.jeopardy.game.exception.InvalidGameFilterException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class GameFilterTest {

    @Test
    void blankTitleMeansNoTitleFilter() {
        assertNull(new GameFilter(null, "", null, null, null).title());
        assertNull(new GameFilter(null, "   ", null, null, null).title());
    }

    @Test
    void blankSeasonMeansNoSeasonFilter() {
        assertNull(new GameFilter("", null, null, null, null).season());
        assertNull(new GameFilter("   ", null, null, null, null).season());
    }

    @Test
    void keepsTitleAsGiven() {
        assertEquals(" Show ", new GameFilter(null, " Show ", null, null, null).title());
    }

    @Test
    void allowsFromEqualToTo() {
        LocalDate date = LocalDate.of(2025, 2, 1);

        GameFilter filter = new GameFilter(null, null, date, date, null);

        assertEquals(date, filter.from());
        assertEquals(date, filter.to());
    }

    @Test
    void allowsOnlyOneDateBound() {
        assertDoesNotThrow(() -> new GameFilter(null, null, LocalDate.of(2025, 2, 1), null, null));
        assertDoesNotThrow(() -> new GameFilter(null, null, null, LocalDate.of(2025, 2, 1), null));
    }

    @Test
    void rejectsFromAfterTo() {
        InvalidGameFilterException exception = assertThrows(
                InvalidGameFilterException.class,
                () -> new GameFilter(
                        null,
                        null,
                        LocalDate.of(2025, 2, 2),
                        LocalDate.of(2025, 2, 1),
                        null
                )
        );

        assertEquals("'from' must not be after 'to'", exception.getMessage());
    }
}
