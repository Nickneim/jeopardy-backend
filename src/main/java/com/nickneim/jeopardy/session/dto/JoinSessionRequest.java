package com.nickneim.jeopardy.session.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Explicit messages aren't localized, so error responses read the same
// whatever the client's Accept-Language. The name is in every broadcast, so
// its length is capped.
public record JoinSessionRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = MAX_NAME_LENGTH, message = "must be at most " + MAX_NAME_LENGTH + " characters")
        String playerName
) {
    public static final int MAX_NAME_LENGTH = 30;

    // Validation runs on the cleaned name, so a name with nothing visible in
    // it (a non-breaking or zero-width space, say) is blank, and "Bob " can't
    // pass for "Bob". @NotBlank alone only ignores ASCII spaces and controls.
    public JoinSessionRequest {
        playerName = playerName == null ? null : trimInvisible(removeControls(playerName));
    }

    // Controls and format characters anywhere in the name go, since every
    // client shows it: a newline breaks layouts, and a direction override
    // (U+202E) reverses the text after it, which could make a name read as
    // someone else's. The zero-width joiner stays, for emoji like 👩‍💻.
    private static String removeControls(String name) {
        StringBuilder kept = new StringBuilder(name.length());

        name.codePoints()
                .filter(codePoint -> codePoint == ZERO_WIDTH_JOINER
                        || !Character.isISOControl(codePoint) && Character.getType(codePoint) != Character.FORMAT)
                .forEach(kept::appendCodePoint);

        return kept.toString();
    }

    private static final int ZERO_WIDTH_JOINER = 0x200D;

    private static String trimInvisible(String name) {
        int start = 0;
        int end = name.length();

        while (start < end && isInvisible(name.codePointAt(start))) {
            start += Character.charCount(name.codePointAt(start));
        }
        while (end > start && isInvisible(name.codePointBefore(end))) {
            end -= Character.charCount(name.codePointBefore(end));
        }

        return name.substring(start, end);
    }

    // Whitespace, other spaces (such as U+00A0), controls, and format
    // characters (such as the zero-width space U+200B).
    private static boolean isInvisible(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.FORMAT;
    }
}
