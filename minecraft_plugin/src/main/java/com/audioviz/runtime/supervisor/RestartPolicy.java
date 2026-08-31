package com.audioviz.runtime.supervisor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;

public final class RestartPolicy {
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(10);
    private static final int CIRCUIT_THRESHOLD = 5;
    private static final List<Duration> DELAYS = List.of(
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        Duration.ofSeconds(4),
        Duration.ofSeconds(8),
        Duration.ofSeconds(15),
        Duration.ofSeconds(30)
    );

    private final DoubleSupplier jitterFactor;
    private final Deque<Instant> failures = new ArrayDeque<>(CIRCUIT_THRESHOLD);

    public RestartPolicy(DoubleSupplier jitterFactor) {
        this.jitterFactor = Objects.requireNonNull(jitterFactor, "jitterFactor");
    }

    public FailureDecision recordFailure(Instant now) {
        Objects.requireNonNull(now, "now");
        prune(now);
        if (failures.size() < CIRCUIT_THRESHOLD) {
            failures.addLast(now);
        }
        return new FailureDecision(
            failureCount(),
            failures.size() >= CIRCUIT_THRESHOLD,
            delayForAttempt(Math.max(0, failures.size() - 1))
        );
    }

    public Duration delayForAttempt(int attempt) {
        int boundedAttempt = Math.max(0, Math.min(attempt, DELAYS.size() - 1));
        double factor = Math.max(0.8, Math.min(1.2, jitterFactor.getAsDouble()));
        long millis = Math.round(DELAYS.get(boundedAttempt).toMillis() * factor);
        return Duration.ofMillis(millis);
    }

    public int failureCount() {
        return failures.size();
    }

    public boolean circuitOpen() {
        return failures.size() >= CIRCUIT_THRESHOLD;
    }

    public void reset() {
        failures.clear();
    }

    private void prune(Instant now) {
        Instant cutoff = now.minus(FAILURE_WINDOW);
        while (!failures.isEmpty() && failures.peekFirst().isBefore(cutoff)) {
            failures.removeFirst();
        }
    }

    public record FailureDecision(int failureCount, boolean circuitOpen, Duration delay) {
        public FailureDecision {
            if (failureCount < 0) {
                throw new IllegalArgumentException("invalid failure count");
            }
            Objects.requireNonNull(delay, "delay");
        }
    }
}
