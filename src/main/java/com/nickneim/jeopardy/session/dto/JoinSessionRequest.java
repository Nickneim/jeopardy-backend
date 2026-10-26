package com.nickneim.jeopardy.session.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

// Explicit messages aren't localized, so error responses read the same
// whatever the client's Accept-Language. The name is in every broadcast, so
// its length is capped, in code points (what JavaScript's [...name].length
// counts), so an emoji counts as 1, not as its 2 UTF-16 units.
public record JoinSessionRequest(
        @NotBlank(message = "must not be blank")
        @CodePointLength(max = MAX_NAME_LENGTH, message = "must be at most " + MAX_NAME_LENGTH + " characters")
        String playerName
) {
    public static final int MAX_NAME_LENGTH = 30;

    // Validation runs on the cleaned name, so a name with nothing visible in
    // it (a non-breaking or zero-width space, say) is blank, and "Bob " can't
    // pass for "Bob". @NotBlank alone only ignores ASCII spaces and controls.
    public JoinSessionRequest {
        playerName = playerName == null ? null : clean(playerName);
    }

    /**
     * Every client shows the name, so: any whitespace (a tab, a newline, a
     * non-breaking space) becomes a plain space, so it still separates words
     * but can't break a layout; the spaces at either end go; other controls,
     * direction marks and overrides (U+202E reverses the text after it, which
     * could make a name read as someone else's) and invisible spaces go
     * everywhere. Other format characters stay, since names and emoji need
     * them: the zero-width non-joiner in Persian or Hindi spelling, the joiner
     * in 👩‍💻, the tags of a flag like 🏴󠁧󠁢󠁳󠁣󠁴󠁿. A name with nothing visible left is
     * blank.
     */
    private static String clean(String name) {
        StringBuilder cleaned = new StringBuilder(name.length());

        name.codePoints().forEach(codePoint -> {
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                cleaned.append(' ');
            } else if (!Character.isISOControl(codePoint) && !isRemovedFormat(codePoint)) {
                cleaned.appendCodePoint(codePoint);
            }
        });

        String trimmed = cleaned.toString().strip();

        return trimmed.codePoints().anyMatch(JoinSessionRequest::isVisible) ? trimmed : "";
    }

    // Direction marks, embeddings, overrides and isolates, and the invisible
    // spaces (zero-width space, word joiner, byte order mark).
    private static boolean isRemovedFormat(int codePoint) {
        return codePoint == 0x061C
                || codePoint == 0x200B
                || codePoint == 0x200E || codePoint == 0x200F
                || codePoint >= 0x202A && codePoint <= 0x202E
                || codePoint == 0x2060
                || codePoint >= 0x2066 && codePoint <= 0x2069
                || codePoint == 0xFEFF;
    }

    private static boolean isVisible(int codePoint) {
        return codePoint != ' ' && Character.getType(codePoint) != Character.FORMAT;
    }
}
