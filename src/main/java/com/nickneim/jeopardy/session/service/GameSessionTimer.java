package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.session.model.SessionCode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
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
    private final Map<SessionCode, ScheduledFuture<?>> timers =
            new ConcurrentHashMap<>();
    private final Map<SessionCode, ScheduledFuture<?>> cleanupTimers =
            new ConcurrentHashMap<>();

    public void schedule(
            SessionCode sessionId,
            Duration delay,
            Runnable action
    ) {
        schedule(timers, sessionId, delay, action);
    }

    public void cancel(SessionCode sessionId) {
        cancel(timers, sessionId);
    }

    public void scheduleCleanup(
            SessionCode sessionId,
            Duration delay,
            Runnable action
    ) {
        schedule(cleanupTimers, sessionId, delay, action);
    }

    public void cancelCleanup(SessionCode sessionId) {
        cancel(cleanupTimers, sessionId);
    }

    private void schedule(
            Map<SessionCode, ScheduledFuture<?>> timers,
            SessionCode sessionId,
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

    private void cancel(Map<SessionCode, ScheduledFuture<?>> timers, SessionCode sessionId) {
        ScheduledFuture<?> future = timers.remove(sessionId);

        if (future != null) {
            future.cancel(false);
        }
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}
