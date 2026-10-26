package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.session.model.SessionCode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class GameSessionTimer {

    private static final Logger log = LoggerFactory.getLogger(GameSessionTimer.class);

    private final ScheduledExecutorService scheduler = newScheduler();

    // Each session has one phase timer and, separately, one cleanup timer
    // that removes the session once it has been idle for too long.
    private final Map<SessionCode, ScheduledFuture<?>> timers =
            new ConcurrentHashMap<>();
    private final Map<SessionCode, ScheduledFuture<?>> cleanupTimers =
            new ConcurrentHashMap<>();

    // By default the executor still runs every delayed task after shutdown,
    // so timers (lobby cleanups wait 30 minutes) would fire against a
    // closed context, e.g. after a devtools restart.
    private static ScheduledExecutorService newScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(4);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

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
                () -> run(sessionId, action),
                delay.toMillis(),
                TimeUnit.MILLISECONDS
        );

        timers.put(sessionId, future);
    }

    // The executor keeps a task's exception in its future, which nobody
    // reads, so without this a failing timeout would stall its session
    // without a trace.
    private static void run(SessionCode sessionId, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.error("A timer of session {} failed", sessionId, exception);
        }
    }

    private void cancel(Map<SessionCode, ScheduledFuture<?>> timers, SessionCode sessionId) {
        ScheduledFuture<?> future = timers.remove(sessionId);

        if (future != null) {
            future.cancel(false);
        }
    }

    // Spring doesn't call this by itself on a component; without it the
    // scheduler's threads outlive the application context.
    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }
}
