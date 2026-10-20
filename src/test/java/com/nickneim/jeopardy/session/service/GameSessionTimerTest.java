package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.session.model.SessionCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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
        SessionCode sessionId = SessionCode.random();
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
        SessionCode sessionId = SessionCode.random();

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
        SessionCode sessionId = SessionCode.random();
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
        SessionCode sessionId = SessionCode.random();

        timer.cancel(sessionId);
    }

    @Test
    void cancel_onlyCancelsTimerForSpecifiedSession()
            throws InterruptedException {
        SessionCode session1 = SessionCode.random();
        SessionCode session2 = SessionCode.random();

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

        SessionCode sessionId = SessionCode.random();
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
