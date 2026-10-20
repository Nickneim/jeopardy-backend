package com.nickneim.jeopardy.session.lobby;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How the lobby list finds the sessions held by other instances
 * ({@code app.session-lobby}). Without {@code peers} it lists only this
 * instance's sessions, as when running a single instance.
 *
 * @param peers       a DNS name that resolves to the address of every ready
 *                    instance, such as a Kubernetes headless Service; blank
 *                    to turn this off
 * @param selfAddress this instance's own address among them (in Kubernetes,
 *                    the pod IP); it isn't asked, because its sessions are
 *                    read directly
 * @param peerPort    the port of the instances' management server, where
 *                    {@link GameSessionsEndpoint} is
 * @param timeout     how long to wait for the other instances; one that
 *                    hasn't answered by then is left out of the list
 */
@ConfigurationProperties(prefix = "app.session-lobby")
public record SessionLobbyProperties(
        String peers,
        String selfAddress,
        int peerPort,
        Duration timeout
) {

    public SessionLobbyProperties {
        peers = peers == null ? "" : peers.strip();
        selfAddress = selfAddress == null ? "" : selfAddress.strip();
        timeout = timeout == null ? Duration.ofSeconds(1) : timeout;
    }

    public boolean enabled() {
        return !peers.isEmpty();
    }
}
