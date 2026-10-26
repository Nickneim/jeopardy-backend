package com.nickneim.jeopardy.session.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Only what breaks a layout goes; invisible characters stay (names are
 * display text, and duplicates are allowed anyway). JoinSessionRequest's
 * tests check that the cleaned name is what gets validated.
 */
class PlayerNamesTest {

    @Test
    void lineBreaksAndTabs_separateWordsAsASpace() {
        assertEquals("Mary Jane Doe Ann", PlayerNames.clean("Mary\nJane\tDoe\u0085Ann"));
        assertEquals("Jean Luc", PlayerNames.clean("Jean Luc"));
    }

    @Test
    void directionAndOtherControls_go() {
        // An override, a right-to-left mark, an isolate, a NUL and a backspace.
        assertEquals("Nico", PlayerNames.clean("N‮i‏c⁦o\u0000\u0008"));
    }

    @Test
    void marksStackedPastTheLimit_go() {
        String accent = "́";

        assertEquals("a" + accent.repeat(4), PlayerNames.clean("a" + accent.repeat(6)));
        // The count starts again at every character.
        assertEquals(("a" + accent.repeat(4)).repeat(2), PlayerNames.clean(("a" + accent.repeat(5)).repeat(2)));
    }

    @Test
    void realStackedMarks_fit() {
        // Vietnamese e with circumflex and tilde.
        assertEquals("Nguyễn", PlayerNames.clean("Nguyễn"));
    }

    @Test
    void spacesAndZeroWidthSpacesAtEitherEnd_go() {
        assertEquals("Nico", PlayerNames.clean("  ​Nico⁠﻿ "));
        assertEquals("", PlayerNames.clean(" ​　\n"));
    }

    @Test
    void invisibleCharactersInside_stay() {
        // A zero-width space, a soft hyphen, a Hangul filler, tags spelling
        // a hidden "hi", the joiner in 👩‍💻 and the Persian non-joiner.
        String name = "Ni​c­oㅤ󠁨󠁩 👩‍💻 می‌خ";

        assertEquals(name, PlayerNames.clean(name));
    }
}
