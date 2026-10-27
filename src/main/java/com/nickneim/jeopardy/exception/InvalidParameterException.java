package com.nickneim.jeopardy.exception;

import java.util.List;

/**
 * A query parameter that isn't valid, such as {@code sort=color} or a
 * {@code from} after {@code to}, or a request body whose fields don't fit
 * together in a way bean validation can't express, such as two custom
 * categories in one column. ApiExceptionHandler turns it into a 400
 * INVALID_REQUEST with this message.
 */
public class InvalidParameterException extends RuntimeException {

    public InvalidParameterException(String message) {
        super(message);
    }

    /** A value outside the parameter's fixed set. */
    public InvalidParameterException(String parameter, List<String> expected) {
        this("Invalid value for '" + parameter + "': expected one of " + String.join(", ", expected));
    }
}
