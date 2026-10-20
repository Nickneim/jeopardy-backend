package com.nickneim.jeopardy.session.lobby;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import com.nickneim.jeopardy.session.model.GameSessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerSessionsTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);

    private final GameSessionSummaryResponse fromA = summary();
    private final GameSessionSummaryResponse fromB = summary();
    private final Set<String> asked = ConcurrentHashMap.newKeySet();
    // Holds a "slow" peer until the test ends.
    private final CountDownLatch release = new CountDownLatch(1);

    private PeerSessions peerSessions;

    @AfterEach
    void tearDown() {
        release.countDown();
        if (peerSessions != null) {
            peerSessions.close();
        }
    }

    @Test
    void asksNobody_whenPeersArentConfigured() {
        peerSessions = new PeerSessions(
                new SessionLobbyProperties("", "", 8081, TIMEOUT),
                name -> { throw new AssertionError("resolved " + name); },
                address -> { throw new AssertionError("asked " + address); }
        );

        assertEquals(List.of(), peerSessions.fetch());
    }

    @Test
    void asksEveryOtherInstance_butNotItself() {
        peerSessions = peers(Map.of(
                "10.0.0.1", List.of(fromA),
                "10.0.0.2", List.of(fromB),
                "10.0.0.3", List.of(summary())
        ), "10.0.0.3");

        assertEquals(Set.of(fromA, fromB), Set.copyOf(peerSessions.fetch()));
        assertEquals(Set.of("10.0.0.1", "10.0.0.2"), asked);
    }

    @Test
    void leavesOutAnInstanceThatFails() {
        peerSessions = new PeerSessions(
                properties(""),
                name -> List.of("10.0.0.1", "10.0.0.2"),
                address -> {
                    if (address.equals("10.0.0.2")) {
                        throw new IOException("Connection refused");
                    }
                    return List.of(fromA);
                }
        );

        assertEquals(List.of(fromA), peerSessions.fetch());
    }

    @Test
    void leavesOutAnInstanceThatDoesntAnswerInTime() {
        peerSessions = new PeerSessions(
                properties(""),
                name -> List.of("10.0.0.1", "10.0.0.2"),
                address -> {
                    if (address.equals("10.0.0.2")) {
                        release.await();
                    }
                    return List.of(fromA);
                }
        );

        long start = System.nanoTime();
        List<GameSessionSummaryResponse> fetched = peerSessions.fetch();
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertEquals(List.of(fromA), fetched);
        assertTrue(took.compareTo(TIMEOUT.multipliedBy(3)) < 0, "took " + took);
    }

    @Test
    void returnsNothing_whenThePeersNameDoesntResolve() {
        peerSessions = new PeerSessions(
                properties(""),
                name -> { throw new UnknownHostException(name); },
                address -> List.of(fromA)
        );

        assertEquals(List.of(), peerSessions.fetch());
    }

    private PeerSessions peers(Map<String, List<GameSessionSummaryResponse>> byAddress, String self) {
        return new PeerSessions(
                properties(self),
                name -> byAddress.keySet().stream().sorted().collect(Collectors.toList()),
                address -> {
                    asked.add(address);
                    return byAddress.get(address);
                }
        );
    }

    private static SessionLobbyProperties properties(String self) {
        return new SessionLobbyProperties("backend-peers", self, 8081, TIMEOUT);
    }

    private static GameSessionSummaryResponse summary() {
        return new GameSessionSummaryResponse(
                UUID.randomUUID(),
                735L,
                "Show #735",
                GameSessionState.WAITING_FOR_START,
                GameRound.JEOPARDY,
                1,
                "Nico",
                Instant.parse("2026-10-09T12:00:00Z"),
                true,
                true,
                0
        );
    }
}
