package com.nickneim.jeopardy.session.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GameSessionTimerTest {

    private GameSessionTimer timer;

    @BeforeEach
    void setUp() {
        timer = new GameSessionTimer();
    }

    @AfterEach
    void tearDown() {
        timer.shutdown();
    }

    @Test
    void schedule_executesActionAfterDelay() throws InterruptedException {
        UUID sessionId = UUID.randomUUID();
        CountDownLatch latch = new CountDownLatch(1);

        timer.schedule(
                sessionId,
                Duration.ofMillis(50),
                latch::countDown
        );

        assertTrue(
                latch.await(1, TimeUnit.SECONDS)
        );
    }

    @Test
    void schedule_replacesExistingTimerForSameSession() throws InterruptedException {
        UUID sessionId = UUID.randomUUID();

        CountDownLatch firstLatch = new CountDownLatch(1);
        CountDownLatch secondLatch = new CountDownLatch(1);

        timer.schedule(
                sessionId,
                Duration.ofMillis(200),
                firstLatch::countDown
        );

        timer.schedule(
                sessionId,
                Duration.ofMillis(50),
                secondLatch::countDown
        );

        assertTrue(
                secondLatch.await(1, TimeUnit.SECONDS)
        );

        assertFalse(
                firstLatch.await(300, TimeUnit.MILLISECONDS)
        );
    }

    @Test
    void cancel_preventsScheduledActionFromRunning()
            throws InterruptedException {
        UUID sessionId = UUID.randomUUID();
        CountDownLatch latch = new CountDownLatch(1);

        timer.schedule(
                sessionId,
                Duration.ofMillis(200),
                latch::countDown
        );

        timer.cancel(sessionId);

        assertFalse(
                latch.await(300, TimeUnit.MILLISECONDS)
        );
    }

    @Test
    void cancel_doesNothingWhenSessionHasNoTimer() {
        UUID sessionId = UUID.randomUUID();

        timer.cancel(sessionId);
    }

    @Test
    void cancel_onlyCancelsTimerForSpecifiedSession()
            throws InterruptedException {
        UUID session1 = UUID.randomUUID();
        UUID session2 = UUID.randomUUID();

        CountDownLatch firstLatch = new CountDownLatch(1);
        CountDownLatch secondLatch = new CountDownLatch(1);

        timer.schedule(
                session1,
                Duration.ofMillis(200),
                firstLatch::countDown
        );

        timer.schedule(
                session2,
                Duration.ofMillis(50),
                secondLatch::countDown
        );

        timer.cancel(session1);

        assertTrue(
                secondLatch.await(1, TimeUnit.SECONDS)
        );

        assertFalse(
                firstLatch.await(300, TimeUnit.MILLISECONDS)
        );
    }

    @Test
    void shutdown_stopsScheduler() throws InterruptedException {
        timer.shutdown();

        UUID sessionId = UUID.randomUUID();
        CountDownLatch latch = new CountDownLatch(1);

        // Scheduling after shutdown should fail because the executor
        // no longer accepts new tasks.
        assertThrows(
                java.util.concurrent.RejectedExecutionException.class,
                () ->
                timer.schedule(
                        sessionId,
                        Duration.ofMillis(50),
                        latch::countDown
                )
        );
    }

}
