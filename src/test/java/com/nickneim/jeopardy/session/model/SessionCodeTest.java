package com.nickneim.jeopardy.session.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCodeTest {

    @Test
    void randomCodesHaveFiveCharactersOfTheAlphabet() {
        Set<SessionCode> codes = new HashSet<>();

        for (int i = 0; i < 1000; i++) {
            SessionCode code = SessionCode.random();
            assertTrue(code.value().matches("[0-9A-HJKMNP-TV-Z]{5}"), code.value());
            codes.add(code);
        }

        // 1000 random codes out of 33.5 million: a repeat would point to a broken generator.
        assertTrue(codes.size() > 990, "only " + codes.size() + " different codes");
    }

    @Test
    void readsLowercaseAndTheUsualMisreadings() {
        assertEquals(SessionCode.of("7KQ20"), SessionCode.of("7kq20"));
        assertEquals("01100", SessionCode.of("OIL00").value());
        assertEquals("01100", SessionCode.of("oil00").value());
    }

    @Test
    void rejectsAnythingElse() {
        for (String text : new String[] {null, "", "ABCD", "ABCDEF", "ABCU1", "AB-12", "AB 12", "ÁBC12"}) {
            assertThrows(IllegalArgumentException.class, () -> SessionCode.of(text), String.valueOf(text));
        }
    }

    @Test
    void isAPlainStringInJson() {
        JsonMapper mapper = JsonMapper.builder().build();

        assertEquals("\"7KQ2M\"", mapper.writeValueAsString(SessionCode.of("7KQ2M")));
        assertEquals(SessionCode.of("7KQ2M"), mapper.readValue("\"7kq2m\"", SessionCode.class));
    }

    @Test
    void sortsByItsText() {
        assertTrue(SessionCode.of("00001").compareTo(SessionCode.of("00002")) < 0);
        assertEquals("7KQ2M", SessionCode.of("7KQ2M").toString());
    }
}
