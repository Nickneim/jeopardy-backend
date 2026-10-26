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

    private static final int ZERO_WIDTH_NON_JOINER = 0x200C;
    private static final int ZERO_WIDTH_JOINER = 0x200D;
    private static final int NEXT_LINE = 0x0085;
    private static final int BRAILLE_BLANK = 0x2800;
    private static final int WAVING_BLACK_FLAG = 0x1F3F4;
    private static final int CANCEL_TAG = 0xE007F;

    /**
     * Every client shows the name, so it keeps only what shows, or changes
     * how what's next to it shows:
     * <ul>
     *     <li>Whitespace (a tab, a newline, a non-breaking space) becomes a
     *     plain space, so it still separates words but can't break a layout,
     *     and the spaces at either end go.</li>
     *     <li>Other controls, and format characters, go everywhere: they're
     *     invisible, so they could make a name look like someone else's
     *     ("Nico" with a soft hyphen) or hide text in it, and the direction
     *     overrides (U+202E) reverse the text after them. Except the ones
     *     names and emoji need: the zero-width non-joiner of Persian or Hindi
     *     spelling, the joiner in 👩‍💻, and the tags of a flag like 🏴󠁧󠁢󠁳󠁣󠁴󠁿 (only
     *     right after its 🏴).</li>
     *     <li>The Hangul fillers, which render as nothing, go too.</li>
     *     <li>A name with nothing visible left (only spaces, joiners, or
     *     combining marks with nothing to combine with) is blank.</li>
     * </ul>
     */
    private static String clean(String name) {
        StringBuilder cleaned = new StringBuilder(name.length());
        boolean inFlag = false;

        for (int codePoint : name.codePoints().toArray()) {
            boolean tag = isTag(codePoint);

            if (isSpace(codePoint)) {
                cleaned.append(' ');
            } else if (tag ? inFlag : isKept(codePoint)) {
                cleaned.appendCodePoint(codePoint);
            }

            inFlag = codePoint == WAVING_BLACK_FLAG || inFlag && tag && codePoint != CANCEL_TAG;
        }

        String trimmed = cleaned.toString().strip();

        return trimmed.codePoints().anyMatch(JoinSessionRequest::isVisible) ? trimmed : "";
    }

    // The braille blank renders as a space, so it counts as one.
    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || codePoint == NEXT_LINE
                || codePoint == BRAILLE_BLANK;
    }

    private static boolean isKept(int codePoint) {
        if (Character.isISOControl(codePoint) || isHangulFiller(codePoint)) {
            return false;
        }

        return Character.getType(codePoint) != Character.FORMAT
                || codePoint == ZERO_WIDTH_NON_JOINER
                || codePoint == ZERO_WIDTH_JOINER;
    }

    private static boolean isTag(int codePoint) {
        return codePoint >= 0xE0000 && codePoint <= CANCEL_TAG;
    }

    private static boolean isHangulFiller(int codePoint) {
        return codePoint == 0x115F || codePoint == 0x1160 || codePoint == 0x3164 || codePoint == 0xFFA0;
    }

    // Spaces, the joiners, flag tags and combining marks only show with
    // something else.
    private static boolean isVisible(int codePoint) {
        int type = Character.getType(codePoint);

        return codePoint != ' '
                && type != Character.FORMAT
                && type != Character.NON_SPACING_MARK
                && type != Character.ENCLOSING_MARK;
    }
}
