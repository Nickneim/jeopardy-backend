package com.nickneim.jeopardy.session.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.security.SecureRandom;

/**
 * A session's id: 5 characters from Crockford's base32 alphabet (digits and
 * uppercase letters without I, L, O and U), short enough to read out or type
 * from a link, such as {@code 7KQ2M}. That's 32^5, about 33.5 million codes.
 * Codes aren't secret: the lobby lists every session, and what proves who a
 * player is is their token.
 *
 * <p>Reading one is lenient, like Crockford's: lowercase is accepted, and O,
 * I and L are read as 0, 1 and 1, the usual misreadings. Anything else that
 * isn't 5 characters of the alphabet is rejected with an
 * IllegalArgumentException, which Spring reports as a bad path or
 * destination variable. In JSON a code is a plain string.
 */
public record SessionCode(String value) implements Comparable<SessionCode> {

    public static final int LENGTH = 5;

    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private static final SecureRandom RANDOM = new SecureRandom();

    public SessionCode {
        value = normalize(value);
    }

    // Spring's converters find this for @PathVariable and @DestinationVariable.
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static SessionCode of(String text) {
        return new SessionCode(text);
    }

    public static SessionCode random() {
        char[] code = new char[LENGTH];

        for (int i = 0; i < LENGTH; i++) {
            code[i] = ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()));
        }

        return new SessionCode(new String(code));
    }

    private static String normalize(String text) {
        if (text == null || text.length() != LENGTH) {
            throw new IllegalArgumentException("A session code has " + LENGTH + " characters");
        }

        char[] code = new char[LENGTH];

        for (int i = 0; i < LENGTH; i++) {
            // Only ASCII: Character.toUpperCase would also turn the long s
            // and the dotless i into S and I.
            char c = text.charAt(i);
            if (c >= 'a' && c <= 'z') {
                c = (char) (c - 'a' + 'A');
            }

            code[i] = switch (c) {
                case 'O' -> '0';
                case 'I', 'L' -> '1';
                default -> c;
            };

            if (ALPHABET.indexOf(code[i]) < 0) {
                throw new IllegalArgumentException("Not a session code character: " + text.charAt(i));
            }
        }

        return new String(code);
    }

    @Override
    public int compareTo(SessionCode other) {
        return value.compareTo(other.value);
    }

    @JsonValue
    @Override
    public String toString() {
        return value;
    }
}
