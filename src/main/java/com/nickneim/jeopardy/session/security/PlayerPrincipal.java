package com.nickneim.jeopardy.session.security;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

/**
 * Who a player is: the session they joined and their player id. It is looked
 * up from the player's secret token, never taken from a client's message.
 */
public record PlayerPrincipal(
        UUID sessionId,
        Long playerId
) implements Principal {

    public PlayerPrincipal {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(playerId, "playerId");
    }

    /** Unique per player. Deliberately not the token, which must stay secret. */
    @Override
    public String getName() {
        return sessionId + ":" + playerId;
    }
}
