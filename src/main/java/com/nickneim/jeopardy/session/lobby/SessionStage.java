package com.nickneim.jeopardy.session.lobby;

import com.nickneim.jeopardy.dto.QueryParameters;
import com.nickneim.jeopardy.exception.InvalidParameterException;
import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.model.GameSessionState;

/**
 * How far a listed session has got, for the lobby's {@code stage} filter and
 * sort. Sorting by stage follows this order.
 */
public enum SessionStage {
    LOBBY,
    JEOPARDY,
    DOUBLE_JEOPARDY,
    FINAL_JEOPARDY,
    FINISHED;

    /** The stage of a session in this state and round, or null for a closed one. */
    public static SessionStage of(GameSessionState state, GameRound round) {
        if (state == null) {
            return null;
        }

        return switch (state) {
            case WAITING_FOR_START -> LOBBY;
            case FINISHED_GAME -> FINISHED;
            case CLOSED -> null;
            default -> round == null ? null : switch (round) {
                case JEOPARDY -> JEOPARDY;
                case DOUBLE_JEOPARDY -> DOUBLE_JEOPARDY;
                case FINAL_JEOPARDY -> FINAL_JEOPARDY;
            };
        };
    }

    /**
     * The {@code stage} parameter, or null without one.
     *
     * @throws InvalidParameterException for an unknown stage
     */
    public static SessionStage parse(String text) {
        return QueryParameters.parse("stage", text, values(), SessionStage::name, null);
    }
}
