package com.nickneim.jeopardy.session.security;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates the secret token a player uses to prove who they are. Player ids
 * are public (every broadcast lists them), so the token is what identifies a
 * player; it must only ever be sent to the player it belongs to.
 */
public final class PlayerTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private PlayerTokens() {
    }

    /** A 256-bit random token, URL-safe base64 without padding (43 characters). */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
