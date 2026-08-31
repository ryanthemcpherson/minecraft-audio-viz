package com.audioviz.runtime.supervisor;

import com.audioviz.runtime.store.RuntimeState.HealthState;
import com.audioviz.runtime.store.RuntimeStore;
import com.audioviz.runtime.store.RuntimeStore.InstalledRuntime;
import com.audioviz.runtime.store.RuntimeStoreException;
import com.audioviz.runtime.store.RuntimeVersionId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class RuntimeSupervisor {
    private static final String NONE = "NONE";

    private final RuntimeStore store;
    private final ProcessLauncher launcher;
    private final LaunchFactory launchFactory;
    private final ScheduledExecutorService coordinator;
    private final Clock clock;
    private final RestartPolicy restartPolicy;
    private final Supplier<String> nonceSource;
    private final Timings timings;
    private final Consumer<SupervisorEvent> eventSink;
    private final AtomicReference<SupervisorSnapshot> published;

    private SupervisorState state = SupervisorState.DISABLED;
    private long generation;
    private InstalledRuntime selected;
    private ManagedRuntimeProcess process;
    private boolean stable;
    private boolean ready;
    private boolean rollbackInProgress;
    private boolean lastHealthHealthy;
    private int restartAttempt;
    private long lastHealthSequence = -1;
    private long rejectedSignals;
    private String reasonCode = NONE;
    private Duration retryDelay;
    private Instant retryAt;
    private ScheduledFuture<?> readinessTask;
    private ScheduledFuture<?> stabilityTask;
    private ScheduledFuture<?> resetTask;
    private ScheduledFuture<?> restartTask;

    public RuntimeSupervisor(
        RuntimeStore store,
        ProcessLauncher launcher,
        LaunchFactory launchFactory,
        ScheduledExecutorService coordinator,
        Clock clock,
        RestartPolicy restartPolicy,
        Supplier<String> nonceSource,
        Timings timings,
        Consumer<SupervisorEvent> eventSink
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.launchFactory = Objects.requireNonNull(launchFactory, "launchFactory");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.restartPolicy = Objects.requireNonNull(restartPolicy, "restartPolicy");
        this.nonceSource = Objects.requireNonNull(nonceSource, "nonceSource");
        this.timings = Objects.requireNonNull(timings, "timings");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.published = new AtomicReference<>(snapshotValue());
    }

    public void start(InstalledRuntime runtime) {
        InstalledRuntime requested = Objects.requireNonNull(runtime, "runtime");
        enqueue(() -> startRequested(requested));
    }

    public void ready(RuntimeReady signal) {
        RuntimeReady received = Objects.requireNonNull(signal, "signal");
        enqueue(() -> readyReceived(received));
    }

    public void health(RuntimeHealth signal) {
        RuntimeHealth received = Objects.requireNonNull(signal, "signal");
        enqueue(() -> healthReceived(received));
    }

    public void processExited(long launchGeneration, int exitCode) {
        if (launchGeneration <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        enqueue(() -> processExitedOnCoordinator(launchGeneration, exitCode));
    }

    public void rendererDisconnected(long launchGeneration) {
        if (launchGeneration <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        enqueue(() -> rendererDisconnectedOnCoordinator(launchGeneration));
    }

    public void retry() {
        enqueue(this::retryRequested);
    }

    public void rollback() {
        enqueue(this::rollbackRequested);
    }

    public void stop(Duration gracefulTimeout) {
        Duration timeout = requireNonNegative(gracefulTimeout, "gracefulTimeout");
        enqueue(() -> stopRequested(timeout));
    }

    public SupervisorSnapshot snapshot() {
        return published.get();
    }

    private void startRequested(InstalledRuntime runtime) {
        invalidateAndStop(timings.shutdownGrace());
        selected = runtime;
        rollbackInProgress = false;
        restartAttempt = 0;
        restartPolicy.reset();
        boolean alreadyHealthy = runtime.state().health() == HealthState.HEALTHY;
        if (!alreadyHealthy && !activate(runtime)) {
            return;
        }
        launch(runtime, alreadyHealthy);
    }

    private boolean activate(InstalledRuntime runtime) {
        try {
            store.activateCandidate(runtime);
            return true;
        } catch (RuntimeStoreException error) {
            failClosed("STORE_FAILURE");
            return false;
        }
    }

    private void launch(InstalledRuntime runtime, boolean alreadyStable) {
        cancelScheduledWork();
        generation++;
        selected = runtime;
        stable = alreadyStable;
        ready = false;
        lastHealthHealthy = false;
        lastHealthSequence = -1;
        retryDelay = null;
        retryAt = null;
        String nonce;
        RuntimeLaunch launch;
        try {
            nonce = Objects.requireNonNull(nonceSource.get(), "launch nonce");
            launch = launchFactory.create(runtime, generation, nonce);
            transition(SupervisorState.STARTING, "STARTING");
            process = Objects.requireNonNull(launcher.launch(launch), "launched process");
            validateProcess(process, launch);
        } catch (Exception error) {
            process = null;
            handleFailure("LAUNCH_FAILED");
            return;
        }

        long launchGeneration = launch.generation();
        process.onExit().whenComplete((exitCode, error) -> {
            int code = error == null && exitCode != null ? exitCode : -1;
            processExited(launchGeneration, code);
        });
        readinessTask = coordinator.schedule(
            () -> readinessTimedOut(launchGeneration),
            timings.readinessTimeout().toNanos(),
            TimeUnit.NANOSECONDS
        );
        publishSnapshot();
    }

    private static void validateProcess(ManagedRuntimeProcess managed, RuntimeLaunch expected) {
        if (
            managed.pid() <= 0 ||
            !managed.isAlive() ||
            managed.launch() == null ||
            managed.launch().generation() != expected.generation() ||
            !managed.launch().version().equals(expected.version())
        ) {
            throw new IllegalStateException("launcher returned an invalid process");
        }
    }

    private void readyReceived(RuntimeReady signal) {
        if (!matchesCurrent(signal)) {
            rejectSignal();
            return;
        }
        cancel(readinessTask);
        readinessTask = null;
        ready = true;
        lastHealthHealthy = true;
        transition(SupervisorState.READY, "READY");
        long launchGeneration = generation;
        if (!stable) {
            stabilityTask = coordinator.schedule(
                () -> stabilityWindowElapsed(launchGeneration),
                timings.stabilityWindow().toNanos(),
                TimeUnit.NANOSECONDS
            );
        }
        resetTask = coordinator.schedule(
            () -> stableResetElapsed(launchGeneration),
            timings.failureResetWindow().toNanos(),
            TimeUnit.NANOSECONDS
        );
    }

    private boolean matchesCurrent(RuntimeReady signal) {
        if (
            state != SupervisorState.STARTING ||
            process == null ||
            selected == null ||
            !process.isAlive()
        ) {
            return false;
        }
        RuntimeLaunch launch = process.launch();
        return signal.generation() == generation &&
            signal.runtimeApi() == launch.runtimeApi() &&
            signal.releaseVersion().equals(launch.version().releaseVersion()) &&
            signal.pid() == process.pid() &&
            secretsEqual(signal.launchNonce(), launch.launchNonce());
    }

    private void healthReceived(RuntimeHealth signal) {
        if (!matchesCurrent(signal)) {
            rejectSignal();
            return;
        }
        lastHealthSequence = signal.sequence();
        lastHealthHealthy = signal.healthy() && process != null && process.isAlive();
        AcceptedHealth acceptedHealth = AcceptedHealth.from(signal);
        if (!lastHealthHealthy) {
            transition(SupervisorState.DEGRADED, "HEALTH_FAILED", acceptedHealth);
            handleFailure("HEALTH_FAILED");
            return;
        }
        if (state == SupervisorState.DEGRADED) {
            transition(SupervisorState.READY, "HEALTH_RECOVERED", acceptedHealth);
        } else {
            transition(SupervisorState.READY, "HEALTHY", acceptedHealth);
        }
    }

    private boolean matchesCurrent(RuntimeHealth signal) {
        return (state == SupervisorState.READY || state == SupervisorState.DEGRADED) &&
            ready &&
            process != null &&
            signal.generation() == generation &&
            signal.sequence() > lastHealthSequence &&
            secretsEqual(signal.launchNonce(), process.launch().launchNonce());
    }

    private void processExitedOnCoordinator(long launchGeneration, int exitCode) {
        if (launchGeneration != generation || process == null) {
            return;
        }
        handleFailure(exitCode == 0 ? "PROCESS_EXITED" : "PROCESS_CRASHED");
    }

    private void rendererDisconnectedOnCoordinator(long launchGeneration) {
        if (
            launchGeneration != generation ||
            process == null ||
            (state != SupervisorState.READY && state != SupervisorState.DEGRADED)
        ) {
            return;
        }
        handleFailure("RENDERER_DISCONNECTED");
    }

    private void readinessTimedOut(long launchGeneration) {
        if (launchGeneration != generation || state != SupervisorState.STARTING) {
            return;
        }
        handleFailure("READINESS_TIMEOUT");
    }

    private void stabilityWindowElapsed(long launchGeneration) {
        if (
            launchGeneration != generation ||
            state != SupervisorState.READY ||
            process == null ||
            !process.isAlive() ||
            !lastHealthHealthy ||
            selected == null
        ) {
            return;
        }
        try {
            store.markHealthy(selected);
            stable = true;
            rollbackInProgress = false;
            transition(SupervisorState.READY, "STABLE");
        } catch (RuntimeStoreException error) {
            handleFailure("STORE_FAILURE");
        }
    }

    private void stableResetElapsed(long launchGeneration) {
        if (
            launchGeneration != generation ||
            state != SupervisorState.READY ||
            process == null ||
            !process.isAlive()
        ) {
            return;
        }
        restartPolicy.reset();
        restartAttempt = 0;
        transition(SupervisorState.READY, "FAILURE_WINDOW_RESET");
    }

    private void handleFailure(String reason) {
        InstalledRuntime failedRuntime = selected;
        boolean failedAfterStability = stable;
        invalidateAndStop(timings.shutdownGrace());
        RestartPolicy.FailureDecision decision = restartPolicy.recordFailure(clock.instant());
        if (decision.circuitOpen()) {
            transition(SupervisorState.FAILED, "CIRCUIT_OPEN");
            return;
        }
        if (!failedAfterStability && !rollbackInProgress && tryAutomaticRollback(failedRuntime)) {
            return;
        }
        if (failedRuntime == null) {
            failClosed(reason);
            return;
        }
        Duration delay = restartPolicy.delayForAttempt(restartAttempt++);
        retryDelay = delay;
        retryAt = clock.instant().plus(delay);
        stable = failedAfterStability;
        transition(SupervisorState.BACKING_OFF, reason);
        long backoffGeneration = generation;
        restartTask = coordinator.schedule(
            () -> {
                if (generation == backoffGeneration && state == SupervisorState.BACKING_OFF) {
                    launch(failedRuntime, failedAfterStability);
                }
            },
            delay.toNanos(),
            TimeUnit.NANOSECONDS
        );
    }

    private boolean tryAutomaticRollback(InstalledRuntime failedRuntime) {
        if (failedRuntime == null) {
            return false;
        }
        Optional<InstalledRuntime> rollback;
        try {
            rollback = store.rollbackTarget(failedRuntime.state().runtimeApi());
        } catch (RuntimeStoreException error) {
            failClosed("STORE_FAILURE");
            return true;
        }
        if (rollback.isEmpty() || rollback.orElseThrow().id().equals(failedRuntime.id())) {
            return false;
        }
        InstalledRuntime target = rollback.orElseThrow();
        rollbackInProgress = true;
        selected = target;
        transition(SupervisorState.ROLLING_BACK, "AUTOMATIC_ROLLBACK");
        if (!activate(target)) {
            return true;
        }
        launch(target, false);
        return true;
    }

    private void retryRequested() {
        if (selected == null) {
            transition(SupervisorState.FAILED, "NO_RUNTIME");
            return;
        }
        InstalledRuntime target = selected;
        boolean wasStable = stable;
        invalidateAndStop(timings.shutdownGrace());
        restartPolicy.reset();
        restartAttempt = 0;
        rollbackInProgress = false;
        launch(target, wasStable);
    }

    private void rollbackRequested() {
        if (selected == null) {
            transition(SupervisorState.FAILED, "NO_RUNTIME");
            return;
        }
        InstalledRuntime current = selected;
        Optional<InstalledRuntime> rollback;
        try {
            rollback = store.rollbackTarget(current.state().runtimeApi());
        } catch (RuntimeStoreException error) {
            failClosed("STORE_FAILURE");
            return;
        }
        if (rollback.isEmpty() || rollback.orElseThrow().id().equals(current.id())) {
            transition(state, "NO_ROLLBACK");
            return;
        }
        InstalledRuntime target = rollback.orElseThrow();
        invalidateAndStop(timings.shutdownGrace());
        rollbackInProgress = true;
        selected = target;
        transition(SupervisorState.ROLLING_BACK, "ROLLBACK_REQUESTED");
        if (activate(target)) {
            launch(target, false);
        }
    }

    private void stopRequested(Duration gracefulTimeout) {
        if (state == SupervisorState.DISABLED) {
            return;
        }
        transition(SupervisorState.STOPPING, "STOPPING");
        invalidateAndStop(gracefulTimeout);
        ready = false;
        stable = false;
        rollbackInProgress = false;
        transition(SupervisorState.DISABLED, "DISABLED");
    }

    private void invalidateAndStop(Duration gracefulTimeout) {
        generation++;
        cancelScheduledWork();
        ManagedRuntimeProcess owned = process;
        process = null;
        ready = false;
        lastHealthHealthy = false;
        if (owned == null) {
            return;
        }
        try {
            owned.requestShutdown();
        } catch (RuntimeException ignored) {
            // Termination remains mandatory even when the control channel is unavailable.
        }
        try {
            owned.terminate(gracefulTimeout, timings.normalTermination());
        } catch (RuntimeException ignored) {
            // The process adapter reports detailed termination diagnostics separately.
        }
    }

    private void failClosed(String reason) {
        cancelScheduledWork();
        transition(SupervisorState.FAILED, reason);
    }

    private void rejectSignal() {
        rejectedSignals++;
        publishSnapshot();
    }

    private void transition(SupervisorState nextState, String reason) {
        transition(nextState, reason, null);
    }

    private void transition(
        SupervisorState nextState,
        String reason,
        AcceptedHealth acceptedHealth
    ) {
        state = Objects.requireNonNull(nextState, "nextState");
        reasonCode = boundedReason(reason);
        publishSnapshot();
        try {
            eventSink.accept(new SupervisorEvent(
                state,
                generation,
                reasonCode,
                clock.instant(),
                Optional.ofNullable(acceptedHealth)
            ));
        } catch (RuntimeException ignored) {
            // Observability cannot mutate supervisor state or stop lifecycle work.
        }
    }

    private void publishSnapshot() {
        published.set(snapshotValue());
    }

    private SupervisorSnapshot snapshotValue() {
        return new SupervisorSnapshot(
            state,
            generation,
            Optional.ofNullable(selected).map(InstalledRuntime::id),
            stable,
            ready,
            process != null && process.isAlive(),
            restartPolicy.circuitOpen(),
            restartPolicy.failureCount(),
            restartAttempt,
            reasonCode,
            clock.instant(),
            Optional.ofNullable(retryAt),
            Optional.ofNullable(retryDelay),
            lastHealthSequence,
            rejectedSignals
        );
    }

    private void cancelScheduledWork() {
        cancel(readinessTask);
        cancel(stabilityTask);
        cancel(resetTask);
        cancel(restartTask);
        readinessTask = null;
        stabilityTask = null;
        resetTask = null;
        restartTask = null;
        retryAt = null;
        retryDelay = null;
    }

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    private void enqueue(Runnable action) {
        try {
            coordinator.execute(action);
        } catch (RejectedExecutionException ignored) {
            // The owning manager has already shut down the coordinator.
        }
    }

    private static boolean secretsEqual(String left, String right) {
        return MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8),
            right.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String boundedReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 64) {
            throw new IllegalArgumentException("invalid supervisor reason");
        }
        for (int index = 0; index < reason.length(); index++) {
            char character = reason.charAt(index);
            if ((character < 'A' || character > 'Z') && character != '_') {
                throw new IllegalArgumentException("invalid supervisor reason");
            }
        }
        return reason;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    @FunctionalInterface
    public interface ProcessLauncher {
        ManagedRuntimeProcess launch(RuntimeLaunch launch) throws Exception;
    }

    @FunctionalInterface
    public interface LaunchFactory {
        RuntimeLaunch create(InstalledRuntime runtime, long generation, String nonce);
    }

    public record Timings(
        Duration readinessTimeout,
        Duration stabilityWindow,
        Duration failureResetWindow,
        Duration shutdownGrace,
        Duration normalTermination
    ) {
        public Timings {
            readinessTimeout = requirePositive(readinessTimeout, "readinessTimeout");
            stabilityWindow = requirePositive(stabilityWindow, "stabilityWindow");
            failureResetWindow = requirePositive(failureResetWindow, "failureResetWindow");
            shutdownGrace = requireNonNegative(shutdownGrace, "shutdownGrace");
            normalTermination = requireNonNegative(normalTermination, "normalTermination");
        }
    }

    public record SupervisorEvent(
        SupervisorState state,
        long generation,
        String reasonCode,
        Instant occurredAt,
        Optional<AcceptedHealth> acceptedHealth
    ) {
        public SupervisorEvent {
            Objects.requireNonNull(state, "state");
            if (generation < 0) {
                throw new IllegalArgumentException("generation must not be negative");
            }
            reasonCode = boundedReason(reasonCode);
            Objects.requireNonNull(occurredAt, "occurredAt");
            acceptedHealth = Objects.requireNonNull(acceptedHealth, "acceptedHealth");
            if (acceptedHealth.isPresent() && !reasonCode.startsWith("HEALTH")) {
                throw new IllegalArgumentException("accepted health requires a health event");
            }
        }
    }

    public record AcceptedHealth(
        long generation,
        long sequence,
        boolean rendererConnected,
        long lastRenderAgeMillis,
        int ingressQueueDepth,
        int renderQueueDepth
    ) {
        public AcceptedHealth {
            if (
                generation <= 0 || sequence < 0 || lastRenderAgeMillis < 0 ||
                ingressQueueDepth < 0 || renderQueueDepth < 0
            ) {
                throw new IllegalArgumentException("invalid accepted runtime health");
            }
        }

        private static AcceptedHealth from(RuntimeHealth signal) {
            return new AcceptedHealth(
                signal.generation(),
                signal.sequence(),
                signal.rendererConnected(),
                signal.lastRenderAgeMillis(),
                signal.ingressQueueDepth(),
                signal.renderQueueDepth()
            );
        }
    }

    public record SupervisorSnapshot(
        SupervisorState state,
        long generation,
        Optional<RuntimeVersionId> runtimeVersion,
        boolean stable,
        boolean ready,
        boolean processAlive,
        boolean circuitOpen,
        int failureCount,
        int restartAttempt,
        String reasonCode,
        Instant updatedAt,
        Optional<Instant> retryAt,
        Optional<Duration> retryDelay,
        long lastHealthSequence,
        long rejectedSignals
    ) {
        public SupervisorSnapshot {
            Objects.requireNonNull(state, "state");
            runtimeVersion = Objects.requireNonNull(runtimeVersion, "runtimeVersion");
            reasonCode = boundedReason(reasonCode);
            Objects.requireNonNull(updatedAt, "updatedAt");
            retryAt = Objects.requireNonNull(retryAt, "retryAt");
            retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
            if (
                generation < 0 || failureCount < 0 || restartAttempt < 0 ||
                lastHealthSequence < -1 || rejectedSignals < 0
            ) {
                throw new IllegalArgumentException("invalid supervisor snapshot");
            }
        }
    }
}
