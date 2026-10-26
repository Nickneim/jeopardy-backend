package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.session.model.SessionCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
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

    // Otherwise a lobby's cleanup would fire against a closed context, up
    // to 30 minutes later.
    @Test
    void shutdown_dropsTimersThatHaveNotFiredYet() throws InterruptedException {
        CountDownLatch phase = new CountDownLatch(1);
        CountDownLatch cleanup = new CountDownLatch(1);
        timer.schedule(SessionCode.random(), Duration.ofMillis(100), phase::countDown);
        timer.scheduleCleanup(SessionCode.random(), Duration.ofMillis(100), cleanup::countDown);

        timer.shutdown();

        assertFalse(phase.await(300, TimeUnit.MILLISECONDS));
        assertFalse(cleanup.await(0, TimeUnit.MILLISECONDS));
    }

    @Test
    void failingAction_isLogged(CapturedOutput output) {
        SessionCode sessionId = SessionCode.random();

        timer.schedule(
                sessionId,
                Duration.ofMillis(10),
                () -> {
                    throw new IllegalStateException("boom");
                }
        );

        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
            assertTrue(output.getOut().contains("A timer of session " + sessionId + " failed"));
            assertTrue(output.getOut().contains("boom"));
        });
    }

    @Test
    void failingCleanup_isLogged(CapturedOutput output) {
        SessionCode sessionId = SessionCode.random();

        timer.scheduleCleanup(
                sessionId,
                Duration.ofMillis(10),
                () -> {
                    throw new IllegalStateException("cleanup failed");
                }
        );

        await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                assertTrue(output.getOut().contains("cleanup failed")));
    }

    @Test
    void closingTheApplicationContext_stopsTheScheduler() {
        new ApplicationContextRunner()
                .withBean(GameSessionTimer.class)
                .run(context -> {
                    GameSessionTimer contextTimer = context.getBean(GameSessionTimer.class);

                    context.close();

                    assertThrows(
                            RejectedExecutionException.class,
                            () -> contextTimer.schedule(SessionCode.random(), Duration.ofMillis(50), () -> {})
                    );
                });
    }

}
