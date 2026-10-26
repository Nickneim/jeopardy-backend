package com.nickneim.jeopardy.game.entity;

import java.util.regex.Pattern;

public enum ClueType {
    TEXT,
    IMAGE,
    SOUND,
    VIDEO;

    // Any character but ASCII whitespace (space, tab, newline, carriage
    // return, form feed, vertical tab): the same list as the database's
    // check_media_url, so the two agree on what's blank.
    private static final Pattern NOT_BLANK = Pattern.compile("[^ \\t\\n\\r\\f\\x0B]");

    /**
     * Whether a clue of this type may have this media URL: none for text, and
     * a non-blank one for anything else (the database's check_media_url).
     */
    public static boolean hasValidMediaUrl(ClueType type, String mediaUrl) {
        return type == TEXT
                ? mediaUrl == null
                : mediaUrl != null && NOT_BLANK.matcher(mediaUrl).find();
    }
}
