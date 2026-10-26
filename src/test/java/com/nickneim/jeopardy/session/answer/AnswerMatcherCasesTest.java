package com.nickneim.jeopardy.session.answer;

import com.nickneim.jeopardy.game.entity.GameRound;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs every case in {@code answer-matching/cases.json}: real answers from
 * the database with responses the user agreed must be accepted or rejected.
 */
class AnswerMatcherCasesTest {

    record Cases(String description, List<Case> cases) {}

    record Case(
            String answer,
            GameRound round,
            Boolean numberExpected,
            String note,
            List<String> accept,
            List<String> reject
    ) {
        boolean questionRequired() {
            return round != null && round != GameRound.JEOPARDY;
        }

        String label() {
            return (round == null ? "" : "[" + round + "] ") + answer;
        }
    }

    @TestFactory
    Stream<DynamicTest> acceptsAndRejectsEveryResponse() throws IOException {
        return cases().stream().flatMap(c -> Stream.concat(
                c.accept().stream().map(response -> DynamicTest.dynamicTest(
                        c.label() + " accepts \"" + response + "\"",
                        () -> assertEquals(true, AnswerMatchers.matches(c.answer(), response, c.questionRequired()),
                                () -> "\"" + response + "\" should match " + c.answer())
                )),
                c.reject().stream().map(response -> DynamicTest.dynamicTest(
                        c.label() + " rejects \"" + response + "\"",
                        () -> assertEquals(false, AnswerMatchers.matches(c.answer(), response, c.questionRequired()),
                                () -> "\"" + response + "\" shouldn't match " + c.answer())
                ))
        ));
    }

    @TestFactory
    Stream<DynamicTest> flagsAnswersThatExpectANumber() throws IOException {
        return cases().stream()
                .filter(c -> c.round() == null)
                .map(c -> DynamicTest.dynamicTest(
                        c.answer(),
                        () -> assertEquals(Boolean.TRUE.equals(c.numberExpected()), AnswerMatchers.expectsNumber(c.answer()))
                ));
    }

    private static List<Case> cases() throws IOException {
        try (InputStream json = AnswerMatcherCasesTest.class.getResourceAsStream("/answer-matching/cases.json")) {
            return JsonMapper.builder().build().readValue(json, Cases.class).cases();
        }
    }
}
