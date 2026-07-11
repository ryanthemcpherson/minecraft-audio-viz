package com.audioviz;

import org.junit.jupiter.api.Test;

import java.net.BindException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketStartupManagerTest {

    @Test
    void failedCandidateIsShutDownBeforeRetryAndOnlyConfirmedStartIsPublished() {
        FakeCandidate first = new FakeCandidate("first", true);
        FakeCandidate second = new FakeCandidate("second", true);
        CandidateSequence candidates = new CandidateSequence(first, second);
        ControlledRetryWait retryWait = new ControlledRetryWait();
        RecordingWorkerLauncher workerLauncher = new RecordingWorkerLauncher();
        RecordingEvents events = new RecordingEvents();
        WebSocketStartupManager<String> manager = new WebSocketStartupManager<>(
            3,
            2_000,
            candidates,
            workerLauncher,
            retryWait,
            events
        );

        manager.start();
        await(first.startCalled);
        assertNull(manager.active());

        first.fail(new BindException("occupied"));
        await(retryWait.entered);
        assertEquals(1, first.shutdownCount.get());
        assertEquals(List.of(1), events.retryAttempts);
        assertNull(manager.active());

        retryWait.release.countDown();
        await(second.startCalled);
        assertNull(manager.active());
        second.succeed();
        await(events.started);

        assertSame(second.value(), manager.active());
        assertEquals(List.of(second.value()), events.published);
        assertEquals(List.of(2), events.startedAttempts);
        assertEquals(2, candidates.created.get());
        assertTrue(manager.stop());
        assertEquals(1, second.shutdownCount.get());
    }

    @Test
    void disableBeforeSuccessDetachesCandidateAndJoinsWorkerWithoutLatePublishOrRetry() {
        FakeCandidate candidate = new FakeCandidate("starting", false);
        CandidateSequence candidates = new CandidateSequence(candidate);
        RecordingWorkerLauncher workerLauncher = new RecordingWorkerLauncher();
        RecordingEvents events = new RecordingEvents();
        WebSocketStartupManager<String> manager = new WebSocketStartupManager<>(
            3,
            2_000,
            candidates,
            workerLauncher,
            ignored -> { },
            events
        );

        manager.start();
        await(candidate.startCalled);

        assertTrue(manager.stop());
        candidate.succeed();

        assertNull(manager.active());
        assertTrue(events.published.isEmpty());
        assertTrue(events.retryAttempts.isEmpty());
        assertEquals(1, candidates.created.get());
        assertEquals(1, candidate.shutdownCount.get());
        assertFalse(workerLauncher.worker.get().isAlive());
        assertFalse(manager.stop());
        assertEquals(1, candidate.shutdownCount.get());
    }

    @Test
    void confirmedCandidateIsDetachedAndShutDownExactlyOnceOnDisable() {
        FakeCandidate candidate = new FakeCandidate("active", true);
        RecordingWorkerLauncher workerLauncher = new RecordingWorkerLauncher();
        RecordingEvents events = new RecordingEvents();
        WebSocketStartupManager<String> manager = new WebSocketStartupManager<>(
            1,
            0,
            new CandidateSequence(candidate),
            workerLauncher,
            ignored -> { },
            events
        );

        manager.start();
        await(candidate.startCalled);
        candidate.succeed();
        await(events.started);

        assertSame(candidate.value(), manager.active());
        assertTrue(manager.stop());
        assertNull(manager.active());
        assertEquals(1, candidate.shutdownCount.get());
        assertFalse(workerLauncher.worker.get().isAlive());

        assertFalse(manager.stop());
        assertEquals(1, candidate.shutdownCount.get());
    }

    @Test
    void disableDuringRetryWaitPreventsAnotherCandidateFromBeingCreated() {
        FakeCandidate first = new FakeCandidate("first", true);
        FakeCandidate forbiddenRetry = new FakeCandidate("forbidden", true);
        CandidateSequence candidates = new CandidateSequence(first, forbiddenRetry);
        ControlledRetryWait retryWait = new ControlledRetryWait();
        RecordingWorkerLauncher workerLauncher = new RecordingWorkerLauncher();
        RecordingEvents events = new RecordingEvents();
        WebSocketStartupManager<String> manager = new WebSocketStartupManager<>(
            3,
            2_000,
            candidates,
            workerLauncher,
            retryWait,
            events
        );

        manager.start();
        await(first.startCalled);
        first.fail(new BindException("occupied"));
        await(retryWait.entered);

        assertFalse(manager.stop());

        assertEquals(1, candidates.created.get());
        assertEquals(1, first.shutdownCount.get());
        assertEquals(0, forbiddenRetry.startCount.get());
        assertTrue(events.published.isEmpty());
        assertFalse(workerLauncher.worker.get().isAlive());
    }

    private static void await(CountDownLatch event) {
        try {
            assertTrue(event.await(5, TimeUnit.SECONDS), "Timed out waiting for event");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for event", exception);
        }
    }

    private static final class FakeCandidate
            implements WebSocketStartupManager.Candidate<String> {
        private final String value;
        private final boolean cancelStartupOnShutdown;
        private final CompletableFuture<Void> startup = new CompletableFuture<>();
        private final CountDownLatch startCalled = new CountDownLatch(1);
        private final AtomicInteger startCount = new AtomicInteger();
        private final AtomicInteger shutdownCount = new AtomicInteger();

        private FakeCandidate(String value, boolean cancelStartupOnShutdown) {
            this.value = value;
            this.cancelStartupOnShutdown = cancelStartupOnShutdown;
        }

        @Override
        public String value() {
            return value;
        }

        @Override
        public void start() {
            startCount.incrementAndGet();
            startCalled.countDown();
        }

        @Override
        public CompletableFuture<Void> startupCompletion() {
            return startup;
        }

        @Override
        public void shutdown() {
            shutdownCount.incrementAndGet();
            if (cancelStartupOnShutdown) {
                startup.cancel(false);
            }
        }

        private void succeed() {
            startup.complete(null);
        }

        private void fail(Exception failure) {
            startup.completeExceptionally(failure);
        }
    }

    private static final class CandidateSequence
            implements WebSocketStartupManager.CandidateFactory<String> {
        private final Deque<FakeCandidate> candidates;
        private final AtomicInteger created = new AtomicInteger();

        private CandidateSequence(FakeCandidate... candidates) {
            this.candidates = new ArrayDeque<>(List.of(candidates));
        }

        @Override
        public synchronized WebSocketStartupManager.Candidate<String> create() {
            created.incrementAndGet();
            return candidates.removeFirst();
        }
    }

    private static final class ControlledRetryWait
            implements WebSocketStartupManager.RetryWait {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void await(long delayMillis) throws InterruptedException {
            entered.countDown();
            release.await();
        }
    }

    private static final class RecordingWorkerLauncher
            implements WebSocketStartupManager.WorkerLauncher {
        private final AtomicReference<Thread> worker = new AtomicReference<>();

        @Override
        public Thread launch(Runnable task) {
            Thread thread = new Thread(task, "websocket-startup-test");
            worker.set(thread);
            thread.start();
            return thread;
        }
    }

    private static final class RecordingEvents
            implements WebSocketStartupManager.Events<String> {
        private final CountDownLatch started = new CountDownLatch(1);
        private final List<String> published = new ArrayList<>();
        private final List<Integer> startedAttempts = new ArrayList<>();
        private final List<Integer> retryAttempts = new ArrayList<>();

        @Override
        public synchronized void onStarted(String value, int attempt) {
            published.add(value);
            startedAttempts.add(attempt);
            started.countDown();
        }

        @Override
        public synchronized void onRetry(
            int attempt,
            int maxAttempts,
            long delayMillis,
            Throwable failure
        ) {
            retryAttempts.add(attempt);
        }

        @Override
        public void onExhausted(int maxAttempts, Throwable failure) { }

        @Override
        public void onShutdownFailure(Throwable failure) { }
    }
}
