package com.nickneim.jeopardy.session.security;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

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

    @Test
    void principalName_identifiesSessionAndPlayer() {
        UUID sessionId = UUID.randomUUID();

        assertEquals(
                sessionId + ":42",
                new PlayerPrincipal(sessionId, 42L).getName()
        );
    }
}
