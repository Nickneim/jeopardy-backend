package com.nickneim.jeopardy.session.model;

/** How the host takes part in a session, chosen in the lobby. */
public enum HostMode {
    /** The host plays like everyone else. The default. */
    CONTESTANT,
    /**
     * The host runs the show as Trebek: they aren't a player, show each
     * clue, open the buzzers, rule on every answer and can change scores.
     */
    TREBEK
}
