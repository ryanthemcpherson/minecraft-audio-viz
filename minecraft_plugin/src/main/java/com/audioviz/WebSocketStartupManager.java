package com.audioviz;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/** Owns WebSocket startup, retry, publication, and shutdown as one lifecycle. */
final class WebSocketStartupManager<T> {

    interface Candidate<T> {
        T value();

        void start();

        CompletionStage<Void> startupCompletion();

        void shutdown();
    }

    @FunctionalInterface
    interface CandidateFactory<T> {
        Candidate<T> create();
    }

    @FunctionalInterface
    interface WorkerLauncher {
        Thread launch(Runnable task);
    }

    @FunctionalInterface
    interface RetryWait {
        void await(long delayMillis) throws InterruptedException;
    }

    interface Events<T> {
        void onStarted(T value, int attempt);

        void onRetry(
            int attempt,
            int maxAttempts,
            long delayMillis,
            Throwable failure
        );

        void onExhausted(int maxAttempts, Throwable failure);

        void onShutdownFailure(Throwable failure);
    }

    private final Object lifecycleLock = new Object();
    private final int maxAttempts;
    private final long retryDelayMillis;
    private final CandidateFactory<T> candidateFactory;
    private final WorkerLauncher workerLauncher;
    private final RetryWait retryWait;
    private final Events<T> events;

    // All lifecycle state below is guarded by lifecycleLock.
    private boolean stopped;
    private Candidate<T> starting;
    private Candidate<T> active;
    private Thread worker;

    WebSocketStartupManager(
        int maxAttempts,
        long retryDelayMillis,
        CandidateFactory<T> candidateFactory,
        WorkerLauncher workerLauncher,
        RetryWait retryWait,
        Events<T> events
    ) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (retryDelayMillis < 0) {
            throw new IllegalArgumentException("retryDelayMillis must not be negative");
        }
        this.maxAttempts = maxAttempts;
        this.retryDelayMillis = retryDelayMillis;
        this.candidateFactory = Objects.requireNonNull(candidateFactory, "candidateFactory");
        this.workerLauncher = Objects.requireNonNull(workerLauncher, "workerLauncher");
        this.retryWait = Objects.requireNonNull(retryWait, "retryWait");
        this.events = Objects.requireNonNull(events, "events");
    }

    void start() {
        synchronized (lifecycleLock) {
            if (stopped || worker != null || starting != null || active != null) {
                return;
            }
            worker = Objects.requireNonNull(
                workerLauncher.launch(this::runStartup),
                "workerLauncher returned null"
            );
        }
    }

    T active() {
        synchronized (lifecycleLock) {
            return active == null ? null : active.value();
        }
    }

    boolean stop() {
        Candidate<T> startingToStop;
        Candidate<T> activeToStop;
        Thread workerToJoin;
        synchronized (lifecycleLock) {
            if (stopped) {
                return false;
            }
            stopped = true;
            startingToStop = starting;
            activeToStop = active;
            workerToJoin = worker;
            starting = null;
            active = null;
            worker = null;
        }

        shutdown(startingToStop);
        if (activeToStop != startingToStop) {
            shutdown(activeToStop);
        }
        interruptAndJoin(workerToJoin);
        return startingToStop != null || activeToStop != null;
    }

    private void runStartup() {
        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                if (isStopped()) {
                    return;
                }

                Candidate<T> candidate = null;
                try {
                    candidate = candidateFactory.create();
                    if (!claimAsStarting(candidate)) {
                        shutdown(candidate);
                        return;
                    }
                    candidate.start();
                    candidate.startupCompletion().toCompletableFuture().get();
                } catch (InterruptedException exception) {
                    releaseInterruptedCandidate(candidate);
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException exception) {
                    if (!handleFailure(candidate, attempt, exception.getCause())) {
                        return;
                    }
                    continue;
                } catch (RuntimeException exception) {
                    if (!handleFailure(candidate, attempt, exception)) {
                        return;
                    }
                    continue;
                }

                synchronized (lifecycleLock) {
                    if (stopped || starting != candidate) {
                        return;
                    }
                    starting = null;
                    active = candidate;
                    events.onStarted(candidate.value(), attempt);
                }
                return;
            }
        } finally {
            synchronized (lifecycleLock) {
                if (worker == Thread.currentThread()) {
                    worker = null;
                }
            }
        }
    }

    private boolean claimAsStarting(Candidate<T> candidate) {
        Objects.requireNonNull(candidate, "candidateFactory returned null");
        synchronized (lifecycleLock) {
            if (stopped) {
                return false;
            }
            starting = candidate;
            return true;
        }
    }

    private boolean handleFailure(Candidate<T> candidate, int attempt, Throwable failure) {
        Candidate<T> ownedCandidate;
        synchronized (lifecycleLock) {
            if (candidate != null && starting == candidate) {
                starting = null;
                ownedCandidate = candidate;
            } else {
                ownedCandidate = null;
            }
        }
        shutdown(ownedCandidate);

        synchronized (lifecycleLock) {
            if (stopped) {
                return false;
            }
            if (attempt >= maxAttempts) {
                events.onExhausted(maxAttempts, failure);
                return false;
            }
            events.onRetry(attempt, maxAttempts, retryDelayMillis, failure);
        }

        try {
            retryWait.await(retryDelayMillis);
            return !isStopped();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void releaseInterruptedCandidate(Candidate<T> candidate) {
        Candidate<T> ownedCandidate;
        synchronized (lifecycleLock) {
            if (candidate != null && starting == candidate) {
                starting = null;
                ownedCandidate = candidate;
            } else {
                ownedCandidate = null;
            }
        }
        shutdown(ownedCandidate);
    }

    private boolean isStopped() {
        synchronized (lifecycleLock) {
            return stopped;
        }
    }

    private void shutdown(Candidate<T> candidate) {
        if (candidate == null) {
            return;
        }
        try {
            candidate.shutdown();
        } catch (RuntimeException exception) {
            events.onShutdownFailure(exception);
        }
    }

    private static void interruptAndJoin(Thread workerToJoin) {
        if (workerToJoin == null || workerToJoin == Thread.currentThread()) {
            return;
        }
        workerToJoin.interrupt();
        boolean interrupted = false;
        while (workerToJoin.isAlive()) {
            try {
                workerToJoin.join();
            } catch (InterruptedException exception) {
                interrupted = true;
                workerToJoin.interrupt();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
