package com.nickneim.jeopardy.game.service;

import com.nickneim.jeopardy.exception.InvalidParameterException;
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
    void trimsTitleAndSeason() {
        GameFilter filter = new GameFilter(" jm ", " Show ", null, null, null);

        assertEquals("jm", filter.season());
        assertEquals("Show", filter.title());
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
        InvalidParameterException exception = assertThrows(
                InvalidParameterException.class,
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
