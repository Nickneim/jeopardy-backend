package com.nickneim.jeopardy.game.entity;

public enum ClueType {
    TEXT,
    IMAGE,
    SOUND,
    VIDEO;

    /**
     * Whether a clue of this type may have this media URL: none for text, and
     * a non-blank one for anything else (the database's check_media_url).
     */
    public static boolean hasValidMediaUrl(ClueType type, String mediaUrl) {
        return type == TEXT
                ? mediaUrl == null
                : mediaUrl != null && !mediaUrl.isBlank();
    }
}