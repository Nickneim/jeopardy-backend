package com.nickneim.jeopardy.session.lobby;

import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The sessions held by the other instances, for the lobby list. Each
 * instance only knows its own, so this asks all of them, in parallel. They're
 * found by resolving {@code app.session-lobby.peers}: in Kubernetes, a
 * headless Service, whose name resolves to the IP of every ready pod. (The
 * JVM caches the answer for 30 seconds, so a new pod can be missing for that
 * long, and a removed one is still asked until then.)
 *
 * <p>The lookup and the answers together take at most the timeout: an
 * instance that fails or doesn't answer by then is left out, so a slow or
 * dying pod hides its own sessions for a moment instead of holding up or
 * breaking the lobby. After a failure, an instance isn't waited for again
 * until it answers: lobby requests ask it in the background (one request at
 * a time; a late answer to the one that failed counts), and once it answers,
 * the next lobby request lists its sessions again. So a removed pod that DNS
 * still names doesn't slow the lobby down.
 */
@Component
public class PeerSessions {

    private static final Logger log = LoggerFactory.getLogger(PeerSessions.class);

    /** Every address a DNS name resolves to. */
    interface Resolver {
        List<String> resolve(String name) throws UnknownHostException;
    }

    private final SessionLobbyProperties properties;
    private final Resolver resolver;
    private final PeerClient client;
    private final InetAddress self;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    // Instances whose last answer failed, with the background request asking
    // them again.
    private final Map<String, CompletableFuture<?>> failing = new ConcurrentHashMap<>();

    @Autowired
    PeerSessions(SessionLobbyProperties properties, PeerClient client) {
        this(properties, PeerSessions::resolveDns, client);
    }

    PeerSessions(SessionLobbyProperties properties, Resolver resolver, PeerClient client) {
        this.properties = properties;
        this.resolver = resolver;
        this.client = client;
        this.self = parseSelf(properties.selfAddress());
    }

    private static List<String> resolveDns(String name) throws UnknownHostException {
        return Arrays.stream(InetAddress.getAllByName(name))
                .map(InetAddress::getHostAddress)
                .toList();
    }

    private static InetAddress parseSelf(String address) {
        if (address.isEmpty()) {
            return null;
        }
        try {
            return InetAddress.getByName(address);
        } catch (UnknownHostException exception) {
            log.warn("Can't read this instance's address {}; it will ask itself too", address);
            return null;
        }
    }

    /**
     * Whether an address is this instance's. Compared as addresses, not text,
     * because the same IPv6 address has several spellings (Kubernetes gives
     * {@code fd00::1}, Java {@code fd00:0:0:0:0:0:0:1}).
     */
    private boolean isSelf(String address) {
        if (self == null) {
            return false;
        }
        try {
            // A literal address, so no lookup.
            return InetAddress.getByName(address).equals(self);
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    /** Every other instance's sessions; none when peers aren't configured. */
    public List<GameSessionSummaryResponse> fetch() {
        if (!properties.enabled()) {
            return List.of();
        }

        long deadline = System.nanoTime() + properties.timeout().toNanos();

        List<String> peers;
        try {
            peers = CompletableFuture
                    .supplyAsync(this::resolvePeers, executor)
                    .orTimeout(properties.timeout().toNanos(), TimeUnit.NANOSECONDS)
                    .join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() instanceof TimeoutException
                    ? new TimeoutException("no answer in " + properties.timeout())
                    : exception.getCause();
            log.warn("Can't find the other instances at {}: {}", properties.peers(), cause.toString());
            return List.of();
        }

        // A removed instance is forgotten once DNS stops naming it.
        failing.keySet().retainAll(peers);

        long remaining = Math.max(0, deadline - System.nanoTime());
        List<CompletableFuture<List<GameSessionSummaryResponse>>> answers = peers.stream()
                .filter(address -> !retryIfFailing(address))
                .map(address -> {
                    CompletableFuture<List<GameSessionSummaryResponse>> answer = fetchAsync(address);
                    // A copy, so that giving up here doesn't end the request.
                    return answer.copy()
                            .orTimeout(remaining, TimeUnit.NANOSECONDS)
                            .exceptionally(failure -> {
                                Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
                                // An answer that's just late still counts.
                                CompletableFuture<?> late = answer.orTimeout(properties.timeout().toNanos(), TimeUnit.NANOSECONDS);
                                if (failing.putIfAbsent(address, late) == null) {
                                    log.warn("Leaving out the sessions of {} until it answers: {}", address, cause.toString());
                                    watch(address, late);
                                }
                                return List.of();
                            });
                })
                .toList();

        return answers.stream()
                .flatMap(answer -> answer.join().stream())
                .toList();
    }

    private List<String> resolvePeers() {
        try {
            return resolver.resolve(properties.peers())
                    .stream()
                    .filter(address -> !isSelf(address))
                    .toList();
        } catch (UnknownHostException exception) {
            throw new CompletionException(exception);
        }
    }

    /**
     * If the instance's last answer failed, asks it again in the background
     * (unless that's already happening) and returns true: it isn't waited for.
     * A request that has just succeeded, whose {@link #watch} hasn't taken
     * the entry off yet, counts as an answer: the instance is asked as usual.
     */
    private boolean retryIfFailing(String address) {
        CompletableFuture<?> last = failing.get(address);

        if (last == null) {
            return false;
        }
        if (last.isDone() && !last.isCompletedExceptionally()) {
            if (failing.remove(address, last)) {
                log.info("{} answers again; listing its sessions", address);
            }
            return false;
        }
        if (last.isDone()) {
            CompletableFuture<?> retry = fetchAsync(address)
                    .orTimeout(properties.timeout().toNanos(), TimeUnit.NANOSECONDS);
            // Only one lobby request starts the retry.
            if (failing.replace(address, last, retry)) {
                watch(address, retry);
            }
        }
        return true;
    }

    /**
     * Takes the instance off {@link #failing} once this request, already
     * stored there, succeeds. Attached only after it's stored, so even a
     * request that is already done finds its entry; and it removes only that
     * entry, never a later one.
     */
    private void watch(String address, CompletableFuture<?> request) {
        request.whenComplete((result, failure) -> {
            if (failure == null && failing.remove(address, request)) {
                log.info("{} answers again; listing its sessions", address);
            }
        });
    }

    private CompletableFuture<List<GameSessionSummaryResponse>> fetchAsync(String address) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return client.fetch(address);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
