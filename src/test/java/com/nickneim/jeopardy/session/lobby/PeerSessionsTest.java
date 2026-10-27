package com.nickneim.jeopardy.session.lobby;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import com.nickneim.jeopardy.session.model.GameSessionState;
import com.nickneim.jeopardy.session.model.SessionCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
    void returnsNothing_whenThePeersNameDoesntResolveInTime() {
        peerSessions = new PeerSessions(
                properties(""),
                name -> {
                    await(release);
                    return List.of("10.0.0.1");
                },
                address -> List.of(fromA)
        );

        long start = System.nanoTime();
        List<GameSessionSummaryResponse> fetched = peerSessions.fetch();
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertEquals(List.of(), fetched);
        assertTrue(took.compareTo(TIMEOUT.multipliedBy(3)) < 0, "took " + took);
    }

    // The lookup and the answer together take longer than the timeout, but
    // each fits in its own: a slow lookup isn't blamed on the instances.
    // (A longer timeout than the other tests, so that each sleep is well
    // clear of it on a busy machine.)
    @Test
    void asksTheInstancesWithTheirOwnTimeout_afterASlowLookup() {
        Duration timeout = Duration.ofMillis(600);
        peerSessions = new PeerSessions(
                new SessionLobbyProperties("backend-peers", "", 8081, timeout),
                name -> {
                    sleep(timeout.multipliedBy(3).dividedBy(5));
                    return List.of("10.0.0.1");
                },
                address -> {
                    sleep(timeout.multipliedBy(3).dividedBy(5));
                    return List.of(fromA);
                }
        );

        assertEquals(List.of(fromA), peerSessions.fetch());
        assertEquals(List.of(fromA), peerSessions.fetch());
    }

    @Test
    void sharesOneLookup_whileItsSlow() {
        AtomicInteger lookups = new AtomicInteger();
        peerSessions = new PeerSessions(
                properties(""),
                name -> {
                    lookups.incrementAndGet();
                    await(release);
                    return List.of("10.0.0.1");
                },
                address -> List.of(fromA)
        );

        assertEquals(List.of(), peerSessions.fetch());
        assertEquals(List.of(), peerSessions.fetch());
        assertEquals(1, lookups.get());
    }

    @Test
    void returnsNothing_afterClosing() {
        peerSessions = peers(Map.of("10.0.0.1", List.of(fromA)), "");
        peerSessions.close();

        assertEquals(List.of(), peerSessions.fetch());
    }

    @Test
    void recognizesItsOwnIpv6Address_inAnySpelling() {
        // Java spells IPv6 addresses out in full; Kubernetes' POD_IP is compressed.
        peerSessions = peers(Map.of(
                "fd00:0:0:0:0:0:0:1", List.of(fromA),
                "fd00:0:0:0:0:0:0:2", List.of(fromB)
        ), "fd00::2");

        assertEquals(List.of(fromA), peerSessions.fetch());
        assertEquals(Set.of("fd00:0:0:0:0:0:0:1"), asked);
    }

    @Test
    void doesntWaitAgainForAnInstanceThatTimedOut_andListsItOnceItAnswers() throws Exception {
        CountDownLatch slowPeer = new CountDownLatch(1);
        peerSessions = new PeerSessions(
                properties(""),
                name -> List.of("10.0.0.1", "10.0.0.2"),
                address -> {
                    if (address.equals("10.0.0.2")) {
                        await(slowPeer);
                        return List.of(fromB);
                    }
                    return List.of(fromA);
                }
        );

        assertEquals(List.of(fromA), peerSessions.fetch());

        long start = System.nanoTime();
        List<GameSessionSummaryResponse> fetched = peerSessions.fetch();
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        assertEquals(List.of(fromA), fetched);
        assertTrue(took.compareTo(TIMEOUT) < 0, "took " + took);

        // Its late answer to the first request is enough.
        slowPeer.countDown();
        assertEventually(() -> Set.copyOf(peerSessions.fetch()).equals(Set.of(fromA, fromB)));
    }

    @Test
    void asksAnInstanceThatFailedAgainInTheBackground_andListsItOnceItAnswers() throws Exception {
        AtomicBoolean down = new AtomicBoolean(true);
        peerSessions = new PeerSessions(
                properties(""),
                name -> List.of("10.0.0.1", "10.0.0.2"),
                address -> {
                    if (address.equals("10.0.0.2")) {
                        if (down.get()) {
                            throw new IOException("Connection refused");
                        }
                        return List.of(fromB);
                    }
                    return List.of(fromA);
                }
        );

        assertEquals(List.of(fromA), peerSessions.fetch());

        down.set(false);
        assertEventually(() -> Set.copyOf(peerSessions.fetch()).equals(Set.of(fromA, fromB)));
    }

    @Test
    void concurrentLobbyRequests_startOneRetryOfAFailedInstance() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        peerSessions = new PeerSessions(
                properties(""),
                name -> List.of("10.0.0.1", "10.0.0.2"),
                address -> {
                    if (address.equals("10.0.0.1")) {
                        return List.of(fromA);
                    }
                    // The first request fails; retries hang, so they stay in flight.
                    if (calls.incrementAndGet() == 1) {
                        throw new IOException("Connection refused");
                    }
                    await(release);
                    return List.of(fromB);
                }
        );
        assertEquals(List.of(fromA), peerSessions.fetch());

        try (ExecutorService lobby = Executors.newFixedThreadPool(10)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<GameSessionSummaryResponse>>> fetches = IntStream.range(0, 10)
                    .mapToObj(i -> lobby.submit(() -> {
                        await(start);
                        return peerSessions.fetch();
                    }))
                    .toList();
            start.countDown();
            for (Future<List<GameSessionSummaryResponse>> fetch : fetches) {
                assertEquals(List.of(fromA), fetch.get(5, TimeUnit.SECONDS));
            }
        }

        assertEventually(() -> calls.get() >= 2);
        Thread.sleep(100);
        assertEquals(2, calls.get());
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

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void assertEventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "condition not met in time");
            Thread.sleep(20);
        }
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
                SessionCode.random(),
                735L,
                "Show #735",
                "6",
                LocalDate.of(1990, 1, 1),
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
