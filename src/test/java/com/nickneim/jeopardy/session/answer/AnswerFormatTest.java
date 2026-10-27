package com.nickneim.jeopardy.session.answer;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnswerFormatTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Lincoln",
            "(Abraham) Lincoln",
            "(duck-bill(ed)) platypus",
            "[Myanmar | Burma]",
            "a linen [closet | cupboard]",
            "(1 of) Chile | Uruguay | Argentina",
            "(2 of) gold | frankincense | myrrh",
            "(2 of) coroner | [coronet | coronel]",
            "(3 of) gold | frankincense | myrrh",
            "bear/bare",
            "2/3",
            "Who's the Boss?",
            "@",
            "<i>Hamlet</i>"
    })
    void acceptsTheStoredFormat(String answer) {
        assertEquals(Optional.empty(), AnswerFormat.problem(answer));
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "a linen [closet | cupboard;      a [ is never closed",
            "a linen closet | cupboard];      a ] has no [ before it",
            "[a [b | c] | d];                 a [ is inside another [ ], and brackets can't be nested",
            "[closet | ];                     a [ ] has an empty wording",
            "(Abraham Lincoln;                a ( is never closed",
            "Abraham) Lincoln;                a ) has no ( before it",
            "Chile | Uruguay;                 a | outside [ ] needs the answer to start with (N of), to make it a list",
            "(1 of) Chile | | Uruguay;        the list has an empty option",
            "(0 of) Chile | Uruguay;          (0 of) asks for no answers",
            "(3 of) Chile | Uruguay;          (3 of) asks for more answers than the list's 2 options",
            "(2 of) Chile;                    (2 of) asks for more answers than the list's 1 option",
            "();                              it has nothing a response could match",
            "(1 of) () | ( );                 it has nothing a response could match"
    })
    void explainsWhatCantBeRead(String answer, String reason) {
        assertEquals(Optional.of(reason), AnswerFormat.problem(answer));
    }
}
