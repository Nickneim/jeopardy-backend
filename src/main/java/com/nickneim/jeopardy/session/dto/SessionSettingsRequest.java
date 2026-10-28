package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.session.model.HostMode;

/**
 * The host's lobby settings, sent with the update-settings action. Every
 * field is optional, and one that is left out keeps its value, but at least
 * one must be set (GameSessionService checks it).
 */
public record SessionSettingsRequest(
        // Whether players can join after the game has started.
        Boolean allowLateJoin,
        // Whether the host plays or runs the show as Trebek.
        HostMode hostMode,
        // Which phases wait for Trebek.
        SessionTimerModesRequest timerModes
) {

    /** Only the late-join setting. */
    public SessionSettingsRequest(Boolean allowLateJoin) {
        this(allowLateJoin, null, null);
    }

    public boolean isEmpty() {
        return allowLateJoin == null && hostMode == null && timerModes == null;
    }
}
