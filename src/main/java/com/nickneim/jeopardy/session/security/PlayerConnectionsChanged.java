package com.nickneim.jeopardy.session.security;

/**
 * A connection of this player opened or closed (PlayerConnections). It
 * doesn't say which: events from two connections can arrive in either order,
 * so a listener asks {@link PlayerConnections#isConnected} for the truth.
 */
public record PlayerConnectionsChanged(PlayerPrincipal player) {}
