package com.nickneim.jeopardy.session.answer;

import java.util.List;

/**
 * A stored answer as players should see it ({@link AnswerMatcher#acceptedAnswers}):
 * a correct response names {@code required} of the {@code options}, each in
 * its first wording. An answer that isn't a list has one option.
 */
public record AcceptedAnswers(int required, List<String> options) {}
