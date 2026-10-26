package com.nickneim.jeopardy.session.security;

import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.SessionCode;

import java.security.Principal;
import java.util.Objects;

/**
 * Who a player is: the session they joined and their player id. It is looked
 * up from the player's secret token, never taken from a client's message.
 *
 * <p>{@code sessionInstance} is the session's {@link GameSession#getInstanceId()}.
 * A closed session's id can be taken by a new session, whose player ids
 * start at 1 again, and a closed session's connections stay open; the
 * instance is what keeps them from acting as the new session's players.
 */
public record PlayerPrincipal(
        SessionCode sessionId,
        long sessionInstance,
        Long playerId
) implements Principal {

    public PlayerPrincipal {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(playerId, "playerId");
    }

    /** The principal of a player of this session. */
    public static PlayerPrincipal of(GameSession session, Long playerId) {
        return new PlayerPrincipal(session.getId(), session.getInstanceId(), playerId);
    }

    /** Whether this player belongs to this very session, not just to its id. */
    public boolean isIn(GameSession session) {
        return sessionId.equals(session.getId()) && sessionInstance == session.getInstanceId();
    }

    /**
     * Unique per player, also across sessions that share an id, since it
     * names their user destinations (/user/queue/errors). Deliberately not
     * the token, which must stay secret.
     */
    @Override
    public String getName() {
        return sessionId + ":" + sessionInstance + ":" + playerId;
    }
}
