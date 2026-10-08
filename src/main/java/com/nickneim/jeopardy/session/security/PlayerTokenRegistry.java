package com.nickneim.jeopardy.session.security;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which player each secret token belongs to. Kept separate from
 * GameSessionService so the WebSocket connection interceptor can use it
 * without depending on the messaging infrastructure (which would be a
 * circular dependency).
 */
@Component
public class PlayerTokenRegistry {

    private final Map<String, PlayerPrincipal> playersByToken =
            new ConcurrentHashMap<>();

    public void register(String token, PlayerPrincipal player) {
        playersByToken.put(token, player);
    }

    /** The player a token belongs to, or empty if it's unknown (or null). */
    public Optional<PlayerPrincipal> find(String token) {
        if (token == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(playersByToken.get(token));
    }

    public void unregister(String token) {
        playersByToken.remove(token);
    }
}
