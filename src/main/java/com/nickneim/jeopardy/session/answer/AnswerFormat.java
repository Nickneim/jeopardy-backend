package com.nickneim.jeopardy.session.answer;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;

/**
 * Checks that a hand-written answer follows the stored format
 * ({@link ExpectedAnswer}: "(N of) A | B", "[A | B]", "(optional)", "A/B").
 *
 * <p>Matching reads any text leniently: an unclosed "[" is just text, and a
 * "|" outside a list is part of the answer. That suits the cleaned-up stored
 * answers, but a host writing a custom show who mistypes the format would
 * get an answer that silently accepts something else, so custom shows are
 * checked with this first. It changes nothing about how answers match.
 */
public final class AnswerFormat {

    private AnswerFormat() {}

    /**
     * Why the answer can't be read, as the end of a sentence ("a [ is never
     * closed"), or empty if it's fine. A blank answer is the caller's to report.
     */
    public static Optional<String> problem(String answer) {
        Matcher list = ExpectedAnswer.LIST.matcher(answer);
        boolean isList = list.matches();
        String body = isList ? list.group(2) : answer;

        Optional<String> marks = marksProblem(body, isList);
        if (marks.isPresent()) {
            return marks;
        }

        if (isList) {
            Optional<String> options = listProblem(new BigInteger(list.group(1)), body);
            if (options.isPresent()) {
                return options;
            }
        }

        if (AnswerMatcher.of(answer).acceptedAnswers().options().isEmpty()) {
            return Optional.of("it has nothing a response could match");
        }

        return Optional.empty();
    }

    // Brackets and parentheses must pair up, brackets can't nest, a bracket
    // can't hold an empty wording, and outside a list "|" only separates
    // wordings inside brackets. A stray "|" is reported last, since an
    // unpaired bracket is usually why it's outside one.
    private static Optional<String> marksProblem(String text, boolean isList) {
        int parentheses = 0;
        int bracketStart = -1;
        boolean strayBar = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            switch (c) {
                case '(' -> parentheses++;
                case ')' -> {
                    if (parentheses == 0) {
                        return Optional.of("a ) has no ( before it");
                    }
                    parentheses--;
                }
                case '[' -> {
                    if (bracketStart >= 0) {
                        return Optional.of("a [ is inside another [ ], and brackets can't be nested");
                    }
                    bracketStart = i;
                }
                case ']' -> {
                    if (bracketStart < 0) {
                        return Optional.of("a ] has no [ before it");
                    }
                    boolean emptyWording = splitAtBars(text.substring(bracketStart + 1, i))
                            .stream()
                            .anyMatch(String::isBlank);
                    if (emptyWording) {
                        return Optional.of("a [ ] has an empty wording");
                    }
                    bracketStart = -1;
                }
                case '|' -> strayBar |= bracketStart < 0 && !isList;
                default -> {}
            }
        }

        if (bracketStart >= 0) {
            return Optional.of("a [ is never closed");
        }
        if (parentheses > 0) {
            return Optional.of("a ( is never closed");
        }
        if (strayBar) {
            return Optional.of("a | outside [ ] needs the answer to start with (N of), to make it a list");
        }

        return Optional.empty();
    }

    // "(N of)" needs N between 1 and the number of options, and no empty option.
    private static Optional<String> listProblem(BigInteger required, String body) {
        List<String> options = splitOutsideBrackets(body);

        if (options.stream().anyMatch(String::isBlank)) {
            return Optional.of("the list has an empty option");
        }
        if (required.signum() == 0) {
            return Optional.of("(0 of) asks for no answers");
        }
        if (required.compareTo(BigInteger.valueOf(options.size())) > 0) {
            return Optional.of(
                    "(" + required + " of) asks for more answers than the list's "
                            + options.size() + (options.size() == 1 ? " option" : " options")
            );
        }

        return Optional.empty();
    }

    private static List<String> splitAtBars(String text) {
        return List.of(text.split("\\|", -1));
    }

    // Brackets are known to pair up and not nest by now.
    private static List<String> splitOutsideBrackets(String text) {
        List<String> parts = new ArrayList<>();
        boolean inBrackets = false;
        int start = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') {
                inBrackets = true;
            } else if (c == ']') {
                inBrackets = false;
            } else if (c == '|' && !inBrackets) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));

        return parts;
    }
}
