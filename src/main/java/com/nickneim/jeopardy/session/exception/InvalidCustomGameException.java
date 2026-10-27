package com.nickneim.jeopardy.session.exception;

import com.nickneim.jeopardy.session.custom.CustomGameProblem;

import java.util.List;

/**
 * A custom show that can't be played, with every problem found, so the host
 * can fix them all at once. A client error (422), like UnplayableGameException
 * for a stored game.
 */
public class InvalidCustomGameException extends RuntimeException {

    private final List<CustomGameProblem> problems;

    public InvalidCustomGameException(List<CustomGameProblem> problems) {
        super("This custom show can't be played: " + problems.size()
                + (problems.size() == 1 ? " problem" : " problems"));
        this.problems = List.copyOf(problems);
    }

    public List<CustomGameProblem> getProblems() {
        return problems;
    }
}
