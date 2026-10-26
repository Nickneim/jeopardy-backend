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

    // Stacked marks past this many on one character are dropped. Real
    // spellings (Vietnamese, Thai, Tibetan) need fewer.
    static final int MAX_MARKS_PER_CHARACTER = 4;

    // Validation runs on the cleaned name, so a name of only spaces is blank,
    // and "Bob " can't pass for "Bob". @NotBlank alone only ignores ASCII
    // spaces and controls.
    public JoinSessionRequest {
        playerName = playerName == null ? null : clean(playerName);
    }

    /**
     * Removes only what would break how clients lay the name out; anything
     * else stays, invisible or not (names are display text, and duplicates
     * are allowed anyway, so a look-alike gains nothing):
     * <ul>
     *     <li>Whitespace (a tab, a newline, a non-breaking space) becomes a
     *     plain space, so it still separates words but can't break a line.</li>
     *     <li>Other control characters and the direction controls go: U+202E,
     *     say, reverses everything after it.</li>
     *     <li>Combining marks stacked past {@link #MAX_MARKS_PER_CHARACTER}
     *     on one character go, so they can't pile up over other lines.</li>
     *     <li>Spaces, including zero-width ones, go at either end.</li>
     * </ul>
     */
    private static String clean(String name) {
        StringBuilder cleaned = new StringBuilder(name.length());
        int marks = 0;

        for (int codePoint : name.codePoints().toArray()) {
            int type = Character.getType(codePoint);
            boolean mark = type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK;
            marks = mark ? marks + 1 : 0;

            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == NEXT_LINE) {
                cleaned.append(' ');
            } else if (!Character.isISOControl(codePoint)
                    && !isDirectionControl(codePoint)
                    && marks <= MAX_MARKS_PER_CHARACTER) {
                cleaned.appendCodePoint(codePoint);
            }
        }

        return trimSpaces(cleaned.toString());
    }

    // A line break, but neither Java whitespace nor a space character.
    private static final int NEXT_LINE = 0x0085;

    // The Arabic letter mark, the left-to-right and right-to-left marks,
    // embeddings, overrides and isolates.
    private static boolean isDirectionControl(int codePoint) {
        return codePoint == 0x061C
                || codePoint == 0x200E || codePoint == 0x200F
                || codePoint >= 0x202A && codePoint <= 0x202E
                || codePoint >= 0x2066 && codePoint <= 0x2069;
    }

    private static String trimSpaces(String name) {
        int start = 0;
        int end = name.length();

        while (start < end && isTrimmed(name.codePointAt(start))) {
            start += Character.charCount(name.codePointAt(start));
        }
        while (end > start && isTrimmed(name.codePointBefore(end))) {
            end -= Character.charCount(name.codePointBefore(end));
        }

        return name.substring(start, end);
    }

    // Spaces, and the zero-width ones: the zero-width space, the word
    // joiner and the zero-width no-break space.
    private static boolean isTrimmed(int codePoint) {
        return codePoint == ' ' || codePoint == 0x200B || codePoint == 0x2060 || codePoint == 0xFEFF;
    }
}
