package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.answer.AcceptedAnswers;

import java.util.List;

/**
 * The correct response as players should see it: a correct answer names
 * {@code required} of the {@code options}, each given in a single wording.
 * An answer that isn't a list has one option.
 */
public record AcceptedAnswersResponse(int required, List<String> options) {

    public static AcceptedAnswersResponse from(AcceptedAnswers accepted) {
        return accepted == null ? null : new AcceptedAnswersResponse(accepted.required(), accepted.options());
    }
}
