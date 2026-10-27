package com.nickneim.jeopardy.dto;

import com.nickneim.jeopardy.exception.InvalidParameterException;

import java.util.Comparator;

/** The {@code direction} parameter of the sortable lists. */
public enum SortDirection {
    ASC("asc"),
    DESC("desc");

    private final String parameterValue;

    SortDirection(String parameterValue) {
        this.parameterValue = parameterValue;
    }

    public String parameterValue() {
        return parameterValue;
    }

    /** {@code ascending} as is, or reversed for DESC. */
    public <T> Comparator<T> apply(Comparator<T> ascending) {
        return this == ASC ? ascending : ascending.reversed();
    }

    /**
     * The direction named {@code text}. Without one, the default sort runs
     * descending (newest first) and every other sort ascending.
     *
     * @throws InvalidParameterException for an unknown direction
     */
    public static SortDirection parse(String text, boolean defaultSort) {
        return QueryParameters.parse(
                "direction",
                text,
                values(),
                SortDirection::parameterValue,
                defaultSort ? DESC : ASC
        );
    }
}
