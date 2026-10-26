package com.nickneim.jeopardy.session.dto;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;

import static com.nickneim.jeopardy.session.dto.AnswerClueRequest.MAX_ANSWER_LENGTH;

// Explicit messages aren't localized, so error responses read the same
// whatever the server's default locale (STOMP has no Accept-Language).
// Answers are broadcast to everyone, so their length is capped, in code
// points like player names (JoinSessionRequest), so an emoji counts as 1.
public record AnswerFinalJeopardyRequest(
        @NotNull(message = "must not be null")
        @CodePointLength(max = MAX_ANSWER_LENGTH, message = "must be at most " + MAX_ANSWER_LENGTH + " characters")
        String answer
) {}
