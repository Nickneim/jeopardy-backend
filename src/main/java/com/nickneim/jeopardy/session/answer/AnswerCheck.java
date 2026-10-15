package com.nickneim.jeopardy.session.answer;

/**
 * The result of checking a response ({@link AnswerMatcher#check}).
 *
 * @param correct           whether the response is accepted
 * @param phrasedAsQuestion whether it starts with the question form ("What is",
 *                          "whats", "waht is" ...), or is a correct response
 *                          that is itself a question said as it is ("Who's the
 *                          Boss?"). Reported in every round, also where the
 *                          question form isn't required.
 */
public record AnswerCheck(boolean correct, boolean phrasedAsQuestion) {}
