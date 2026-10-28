package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.game.entity.ClueType;
import com.nickneim.jeopardy.session.model.HostMode;

import java.util.Set;

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
        SessionTimerModesRequest timerModes,
        // The clue types left out of the game, replacing the ones before.
        // Jackson reads it as an EnumSet, so a null element is a body error.
        Set<ClueType> disabledClueTypes
) {

    /** Only the late-join setting. */
    public SessionSettingsRequest(Boolean allowLateJoin) {
        this(allowLateJoin, null, null);
    }

    /** Every setting but the clue types. */
    public SessionSettingsRequest(Boolean allowLateJoin, HostMode hostMode, SessionTimerModesRequest timerModes) {
        this(allowLateJoin, hostMode, timerModes, null);
    }

    public boolean isEmpty() {
        return allowLateJoin == null && hostMode == null && timerModes == null && disabledClueTypes == null;
    }
}
