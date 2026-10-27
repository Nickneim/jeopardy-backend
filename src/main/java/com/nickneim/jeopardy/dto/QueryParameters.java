package com.nickneim.jeopardy.dto;

import com.nickneim.jeopardy.exception.InvalidParameterException;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.Function;

/** How the list endpoints read their optional query parameters. */
public final class QueryParameters {

    private QueryParameters() {}

    /** The text without leading and trailing spaces, or null if it's null or blank. */
    public static String trimToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    /**
     * The value named {@code text}, matched case-insensitively against each
     * value's name. A missing or blank text gives {@code absent}.
     *
     * @throws InvalidParameterException for any other text
     */
    public static <E> E parse(String parameter, String text, E[] values, Function<E, String> name, E absent) {
        String trimmed = trimToNull(text);

        if (trimmed == null) {
            return absent;
        }

        String wanted = trimmed.toLowerCase(Locale.ROOT);

        return Arrays.stream(values)
                .filter(value -> name.apply(value).toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst()
                .orElseThrow(() -> new InvalidParameterException(
                        parameter,
                        Arrays.stream(values).map(name).toList()
                ));
    }
}
