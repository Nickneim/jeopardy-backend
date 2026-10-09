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

    // Each session has one phase timer and, separately, one cleanup timer
    // that removes the session once it has been idle for too long.
    private final Map<UUID, ScheduledFuture<?>> timers =
            new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> cleanupTimers =
            new ConcurrentHashMap<>();

    public void schedule(
            UUID sessionId,
            Duration delay,
            Runnable action
    ) {
        schedule(timers, sessionId, delay, action);
    }

    public void cancel(UUID sessionId) {
        cancel(timers, sessionId);
    }

    public void scheduleCleanup(
            UUID sessionId,
            Duration delay,
            Runnable action
    ) {
        schedule(cleanupTimers, sessionId, delay, action);
    }

    public void cancelCleanup(UUID sessionId) {
        cancel(cleanupTimers, sessionId);
    }

    private void schedule(
            Map<UUID, ScheduledFuture<?>> timers,
            UUID sessionId,
            Duration delay,
            Runnable action
    ) {
        cancel(timers, sessionId);

        ScheduledFuture<?> future = scheduler.schedule(
                action,
                delay.toMillis(),
                TimeUnit.MILLISECONDS
        );

        timers.put(sessionId, future);
    }

    private void cancel(Map<UUID, ScheduledFuture<?>> timers, UUID sessionId) {
        ScheduledFuture<?> future = timers.remove(sessionId);

        if (future != null) {
            future.cancel(false);
        }
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}
