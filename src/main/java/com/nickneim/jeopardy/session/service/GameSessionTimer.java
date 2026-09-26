package com.nickneim.jeopardy.session.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class GameSessionTimer {

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(4);

    private final Map<UUID, ScheduledFuture<?>> timers =
            new ConcurrentHashMap<>();

    public void schedule(
            UUID sessionId,
            Duration delay,
            Runnable action
    ) {
        cancel(sessionId);

        ScheduledFuture<?> future = scheduler.schedule(
                action,
                delay.toMillis(),
                TimeUnit.MILLISECONDS
        );

        timers.put(sessionId, future);
    }

    public void cancel(UUID sessionId) {
        ScheduledFuture<?> future = timers.remove(sessionId);

        if (future != null) {
            future.cancel(false);
        }
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}
