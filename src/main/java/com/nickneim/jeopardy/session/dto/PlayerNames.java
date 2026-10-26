package com.nickneim.jeopardy.session.dto;

/**
 * Cleans a player's name before it's validated and shown to everyone.
 * Removes only what would break how clients lay the name out; anything else
 * stays, invisible or not (names are display text, and duplicates are
 * allowed anyway, so a look-alike gains nothing):
 * <ul>
 *     <li>Whitespace (a tab, a newline, a non-breaking space) becomes a plain
 *     space, so it still separates words but can't break a line.</li>
 *     <li>Other control characters and the direction controls go: U+202E,
 *     say, reverses everything after it.</li>
 *     <li>Combining marks stacked past {@link #MAX_MARKS_PER_CHARACTER} on
 *     one character go, so they can't pile up over other lines.</li>
 *     <li>Spaces, including zero-width ones, go at either end, so a name of
 *     only those is blank.</li>
 * </ul>
 * This rule was agreed with the user: don't extend it to other invisible
 * characters without asking.
 */
public final class PlayerNames {

    /** Stacked marks past this many on one character are dropped. Real spellings (Vietnamese, Thai, Tibetan) need fewer. */
    public static final int MAX_MARKS_PER_CHARACTER = 4;

    // A line break, but neither Java whitespace nor a space character.
    private static final int NEXT_LINE = 0x0085;

    private PlayerNames() {
    }

    public static String clean(String name) {
        StringBuilder cleaned = new StringBuilder(name.length());

        // Marks on the last character that takes up space. Only such a
        // character starts the count again: a removed one (a NUL, say) or a
        // zero-width one between two groups of marks would otherwise let
        // them stack on the same spot.
        int marks = 0;

        for (int codePoint : name.codePoints().toArray()) {
            int type = Character.getType(codePoint);

            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == NEXT_LINE) {
                cleaned.append(' ');
                marks = 0;
            } else if (Character.isISOControl(codePoint) || isDirectionControl(codePoint)) {
                continue;
            } else if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK) {
                if (++marks <= MAX_MARKS_PER_CHARACTER) {
                    cleaned.appendCodePoint(codePoint);
                }
            } else {
                cleaned.appendCodePoint(codePoint);
                if (type != Character.FORMAT) {
                    marks = 0;
                }
            }
        }

        return trimSpaces(cleaned.toString());
    }

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
