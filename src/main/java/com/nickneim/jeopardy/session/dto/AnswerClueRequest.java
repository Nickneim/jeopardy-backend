package com.nickneim.jeopardy.session.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Explicit messages aren't localized, so error responses read the same
// whatever the server's default locale (STOMP has no Accept-Language).
// Answers are broadcast to everyone, so their length is capped.
public record AnswerClueRequest(
        @NotNull(message = "must not be null")
        @Size(max = MAX_ANSWER_LENGTH, message = "must be at most " + MAX_ANSWER_LENGTH + " characters")
        String answer
) {
    /** The longest answer accepted, here and in Final Jeopardy. */
    public static final int MAX_ANSWER_LENGTH = 200;
}
