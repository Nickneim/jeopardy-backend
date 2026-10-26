package com.nickneim.jeopardy.session.security;

import com.nickneim.jeopardy.session.model.SessionCode;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTokensTest {

    @Test
    void generate_returns256BitUrlSafeToken() {
        String token = PlayerTokens.generate();

        assertTrue(
                token.matches("[A-Za-z0-9_-]{43}"),
                "unexpected token format: " + token
        );
    }

    @Test
    void generate_returnsADifferentTokenEachTime() {
        Set<String> tokens = new HashSet<>();

        for (int i = 0; i < 1000; i++) {
            tokens.add(PlayerTokens.generate());
        }

        assertEquals(1000, tokens.size());
    }

    // It names the player's user destinations, so a player of an earlier
    // session with the same id and player id must have another.
    @Test
    void principalName_identifiesSessionInstanceAndPlayer() {
        SessionCode sessionId = SessionCode.random();

        assertEquals(
                sessionId + ":7:42",
                new PlayerPrincipal(sessionId, 7, 42L).getName()
        );
        assertNotEquals(
                new PlayerPrincipal(sessionId, 7, 42L).getName(),
                new PlayerPrincipal(sessionId, 8, 42L).getName()
        );
    }
}
