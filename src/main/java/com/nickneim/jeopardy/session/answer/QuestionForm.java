package com.nickneim.jeopardy.session.answer;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "What is ..." at the start of a response. Any question word works with
 * any kind of answer, followed by is, are, was, were, 's or 're ("What is",
 * "Who's", "whats", "Where were"). A few typos of the question word are known
 * to mean the question form ("waht", "hwo"), so they count too.
 */
final class QuestionForm {

    private static final Pattern PREFIX = Pattern.compile(
            "^[\\s\"'‘“(]*"
                    + "(?:where|wehre|whre|wher|what|waht|whta|wht|wat|when|wehn|whn|which|who|hwo|woh|why|how)"
                    + "(?:\\s*['’](?:s|re)|s|\\s+(?:is|are|was|were))\\b\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private QuestionForm() {
    }

    /** What follows the question form, if the text starts with one. */
    static Optional<String> strip(String text) {
        Matcher matcher = PREFIX.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String rest = text.substring(matcher.end());
        return rest.isBlank() ? Optional.empty() : Optional.of(rest);
    }

    static boolean isQuestion(String text) {
        return strip(text).isPresent();
    }
}
