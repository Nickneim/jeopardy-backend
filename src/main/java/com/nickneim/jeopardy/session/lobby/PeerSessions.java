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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The sessions held by the other instances, for the lobby list. Each
 * instance only knows its own, so this asks all of them, in parallel. They're
 * found by resolving {@code app.session-lobby.peers}: in Kubernetes, a
 * headless Service, whose name resolves to the IP of every ready pod. (The
 * JVM caches the answer for 30 seconds, so a new pod can be missing for that
 * long, and a removed one fails until then.)
 *
 * <p>An instance that fails or doesn't answer within the timeout is left
 * out, so a slow or dying pod hides its own sessions for a moment instead of
 * holding up or breaking the lobby.
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
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    PeerSessions(SessionLobbyProperties properties, PeerClient client) {
        this(properties, PeerSessions::resolveDns, client);
    }

    PeerSessions(SessionLobbyProperties properties, Resolver resolver, PeerClient client) {
        this.properties = properties;
        this.resolver = resolver;
        this.client = client;
    }

    private static List<String> resolveDns(String name) throws UnknownHostException {
        return Arrays.stream(InetAddress.getAllByName(name))
                .map(InetAddress::getHostAddress)
                .toList();
    }

    /** Every other instance's sessions; none when peers aren't configured. */
    public List<GameSessionSummaryResponse> fetch() {
        if (!properties.enabled()) {
            return List.of();
        }

        List<String> peers;
        try {
            peers = resolver.resolve(properties.peers())
                    .stream()
                    .filter(address -> !address.equals(properties.selfAddress()))
                    .toList();
        } catch (UnknownHostException exception) {
            log.warn("Can't find the other instances at {}: {}", properties.peers(), exception.getMessage());
            return List.of();
        }

        List<CompletableFuture<List<GameSessionSummaryResponse>>> answers = peers.stream()
                .map(address -> CompletableFuture
                        .supplyAsync(() -> fetchFrom(address), executor)
                        .orTimeout(properties.timeout().toMillis(), TimeUnit.MILLISECONDS)
                        .exceptionally(failure -> {
                            Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
                            log.warn("Leaving out the sessions of {}: {}", address, cause.toString());
                            return List.of();
                        }))
                .toList();

        return answers.stream()
                .flatMap(answer -> answer.join().stream())
                .toList();
    }

    private List<GameSessionSummaryResponse> fetchFrom(String address) {
        try {
            return client.fetch(address);
        } catch (Exception exception) {
            throw new CompletionException(exception);
        }
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
