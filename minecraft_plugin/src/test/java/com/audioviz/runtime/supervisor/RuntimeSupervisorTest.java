package com.audioviz.runtime.supervisor;

import static com.audioviz.runtime.supervisor.SupervisorState.BACKING_OFF;
import static com.audioviz.runtime.supervisor.SupervisorState.DISABLED;
import static com.audioviz.runtime.supervisor.SupervisorState.FAILED;
import static com.audioviz.runtime.supervisor.SupervisorState.READY;
import static com.audioviz.runtime.supervisor.SupervisorState.STARTING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audioviz.runtime.release.RuntimePlatform;
import com.audioviz.runtime.store.RuntimeState;
import com.audioviz.runtime.store.RuntimeState.HealthState;
import com.audioviz.runtime.store.RuntimeStore;
import com.audioviz.runtime.store.RuntimeStore.InstalledRuntime;
import com.audioviz.runtime.store.RuntimeVersionId;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuntimeSupervisorTest {
    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STABILITY_WINDOW = Duration.ofSeconds(60);
    private static final Duration FAILURE_RESET = Duration.ofMinutes(10);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(10);
    private static final String NONCE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String DIGEST = "a".repeat(64);

    private MutableClock clock;
    private ManualScheduledExecutor coordinator;
    private RuntimeStore store;
    private FakeLauncher launcher;
    private RuntimeSupervisor supervisor;
    private InstalledRuntime candidate;
    private InstalledRuntime previous;

    @BeforeEach
    void setUp() throws Exception {
        clock = new MutableClock(Instant.parse("2026-08-31T12:00:00Z"));
        coordinator = new ManualScheduledExecutor(clock);
        store = mock(RuntimeStore.class);
        launcher = new FakeLauncher();
        candidate = runtime("1.2.0", 12, HealthState.CANDIDATE);
        previous = runtime("1.1.0", 11, HealthState.HEALTHY);
        when(store.rollbackTarget(1)).thenReturn(Optional.empty());
        supervisor = supervisor(new RestartPolicy(() -> 1.0));
    }

    @Test
    void candidateBecomesLastKnownGoodOnlyAfterReadyAndStabilityWindow() throws Exception {
        start(candidate);
        FakeProcess process = launcher.onlyProcess();

        supervisor.ready(ready(process));
        runCurrent();
        assertEquals(READY, supervisor.snapshot().state());
        verify(store, never()).markHealthy(candidate);

        advance(Duration.ofSeconds(59));
        verify(store, never()).markHealthy(candidate);
        advance(Duration.ofSeconds(1));

        verify(store).markHealthy(candidate);
        assertTrue(supervisor.snapshot().stable());
    }

    @Test
    void readinessRequiresCurrentGenerationNonceApiVersionAndPid() {
        start(candidate);
        FakeProcess process = launcher.onlyProcess();
        RuntimeLaunch launch = process.launch();

        supervisor.ready(new RuntimeReady("1.2.0", 1, launch.generation() - 1, NONCE, 100, Set.of()));
        supervisor.ready(new RuntimeReady("1.2.0", 1, launch.generation(), "B".repeat(43), 100, Set.of()));
        supervisor.ready(new RuntimeReady("1.2.0", 2, launch.generation(), NONCE, 100, Set.of()));
        supervisor.ready(new RuntimeReady("1.1.0", 1, launch.generation(), NONCE, 100, Set.of()));
        supervisor.ready(new RuntimeReady("1.2.0", 1, launch.generation(), NONCE, 101, Set.of()));
        runCurrent();

        assertEquals(STARTING, supervisor.snapshot().state());
        assertEquals(5, supervisor.snapshot().rejectedSignals());
    }

    @Test
    void readinessTimeoutStopsProcessAndBacksOff() {
        start(candidate);
        FakeProcess process = launcher.onlyProcess();

        advance(READINESS_TIMEOUT);

        assertEquals(BACKING_OFF, supervisor.snapshot().state());
        assertEquals(1, process.shutdownRequests);
        assertEquals(1, process.terminations);
        assertEquals(Duration.ofSeconds(1), supervisor.snapshot().retryDelay().orElseThrow());
    }

    @Test
    void candidateExitRollsBackToCompatibleLastKnownGood() throws Exception {
        when(store.rollbackTarget(1)).thenReturn(Optional.of(previous));
        start(candidate);
        FakeProcess failed = launcher.onlyProcess();

        failed.exit(17);
        runCurrent();

        assertEquals(2, launcher.processes.size());
        assertEquals(previous.id(), launcher.onlyProcess().launch().version());
        assertEquals(STARTING, supervisor.snapshot().state());
        verify(store).activateCandidate(previous);
    }

    @Test
    void unhealthyCandidateHealthRollsBack() throws Exception {
        when(store.rollbackTarget(1)).thenReturn(Optional.of(previous));
        start(candidate);
        FakeProcess process = launcher.onlyProcess();
        supervisor.ready(ready(process));
        runCurrent();

        supervisor.health(unhealthy(process, 1));
        runCurrent();

        assertEquals(2, launcher.processes.size());
        assertEquals(previous.id(), launcher.onlyProcess().launch().version());
        verify(store, never()).markHealthy(candidate);
    }

    @Test
    void healthySignalsRequireStrictlyIncreasingSequence() {
        start(candidate);
        FakeProcess process = launcher.onlyProcess();
        supervisor.ready(ready(process));
        runCurrent();

        supervisor.health(healthy(process, 2));
        supervisor.health(healthy(process, 2));
        supervisor.health(healthy(process, 1));
        runCurrent();

        assertEquals(2, supervisor.snapshot().rejectedSignals());
        assertEquals(2, supervisor.snapshot().lastHealthSequence());
    }

    @Test
    void stableRuntimeExitUsesBackoffAndRelaunchesSameVersion() throws Exception {
        startAndStabilize(candidate);
        FakeProcess first = launcher.onlyProcess();

        first.exit(9);
        runCurrent();
        assertEquals(BACKING_OFF, supervisor.snapshot().state());
        assertEquals(1, launcher.processes.size());

        advance(Duration.ofMillis(999));
        assertEquals(1, launcher.processes.size());
        advance(Duration.ofMillis(1));
        assertEquals(2, launcher.processes.size());
        assertEquals(candidate.id(), launcher.onlyProcess().launch().version());
    }

    @Test
    void retryDelaysFollowBoundedExponentialSequence() {
        RestartPolicy policy = new RestartPolicy(() -> 1.0);
        assertEquals(
            List.of(1L, 2L, 4L, 8L, 15L, 30L, 30L),
            List.of(0, 1, 2, 3, 4, 5, 99).stream()
                .map(attempt -> policy.delayForAttempt(attempt).toSeconds())
                .toList()
        );
    }

    @Test
    void retryJitterIsClampedToTwentyPercent() {
        assertEquals(Duration.ofMillis(800), new RestartPolicy(() -> -10).delayForAttempt(0));
        assertEquals(Duration.ofMillis(1200), new RestartPolicy(() -> 10).delayForAttempt(0));
        assertEquals(Duration.ofMillis(900), new RestartPolicy(() -> 0.9).delayForAttempt(0));
    }

    @Test
    void fiveFailuresWithinTenMinutesOpenCircuit() {
        start(candidate);

        for (int index = 0; index < 5; index++) {
            launcher.onlyProcess().exit(20 + index);
            runCurrent();
            if (index < 4) {
                Duration delay = supervisor.snapshot().retryDelay().orElseThrow();
                advance(delay);
            }
        }

        assertEquals(FAILED, supervisor.snapshot().state());
        assertTrue(supervisor.snapshot().circuitOpen());
        assertEquals(5, launcher.processes.size());
    }

    @Test
    void oldFailuresAgeOutOfCircuitWindow() {
        RestartPolicy policy = new RestartPolicy(() -> 1.0);
        Instant now = clock.instant();
        for (int index = 0; index < 4; index++) {
            assertFalse(policy.recordFailure(now.plusSeconds(index)).circuitOpen());
        }
        assertFalse(policy.recordFailure(now.plus(Duration.ofMinutes(11))).circuitOpen());
        assertEquals(1, policy.failureCount());
    }

    @Test
    void tenStableMinutesResetFailureHistoryAndAttempts() throws Exception {
        start(candidate);
        FakeProcess first = launcher.onlyProcess();
        first.exit(1);
        runCurrent();
        advance(supervisor.snapshot().retryDelay().orElseThrow());
        FakeProcess second = launcher.onlyProcess();
        supervisor.ready(ready(second));
        runCurrent();
        advance(FAILURE_RESET);

        assertEquals(0, supervisor.snapshot().failureCount());
        second.exit(2);
        runCurrent();
        assertEquals(Duration.ofSeconds(1), supervisor.snapshot().retryDelay().orElseThrow());
    }

    @Test
    void explicitRetryClosesCircuitAndLaunchesAgain() {
        start(candidate);
        for (int index = 0; index < 5; index++) {
            launcher.onlyProcess().exit(30 + index);
            runCurrent();
            if (index < 4) {
                advance(supervisor.snapshot().retryDelay().orElseThrow());
            }
        }

        supervisor.retry();
        runCurrent();

        assertEquals(STARTING, supervisor.snapshot().state());
        assertFalse(supervisor.snapshot().circuitOpen());
        assertEquals(6, launcher.processes.size());
    }

    @Test
    void explicitRollbackActivatesCompatibleTarget() throws Exception {
        when(store.rollbackTarget(1)).thenReturn(Optional.of(previous));
        startAndStabilize(candidate);

        supervisor.rollback();
        runCurrent();

        assertEquals(previous.id(), launcher.onlyProcess().launch().version());
        verify(store).activateCandidate(previous);
    }

    @Test
    void staleExitAndScheduledReadinessTimeoutCannotAffectNewLaunch() throws Exception {
        start(candidate);
        FakeProcess old = launcher.onlyProcess();
        supervisor.retry();
        runCurrent();
        FakeProcess current = launcher.onlyProcess();
        supervisor.ready(ready(current));
        runCurrent();

        old.exit(4);
        advance(READINESS_TIMEOUT);

        assertEquals(READY, supervisor.snapshot().state());
        assertEquals(2, launcher.processes.size());
    }

    @Test
    void stopIsIdempotentAndLateCallbacksCannotRestart() {
        start(candidate);
        FakeProcess process = launcher.onlyProcess();
        long generation = process.launch().generation();

        supervisor.stop(SHUTDOWN_GRACE);
        supervisor.stop(SHUTDOWN_GRACE);
        runCurrent();
        supervisor.ready(ready(process));
        supervisor.processExited(generation, 0);
        advance(Duration.ofMinutes(1));

        assertEquals(DISABLED, supervisor.snapshot().state());
        assertEquals(1, process.shutdownRequests);
        assertEquals(1, process.terminations);
        assertEquals(1, launcher.processes.size());
    }

    @Test
    void replacementNeverLeavesTwoProcessesOwned() {
        start(candidate);
        FakeProcess first = launcher.onlyProcess();

        InstalledRuntime upgrade = runtime("1.2.1", 13, HealthState.CANDIDATE);
        supervisor.start(upgrade);
        runCurrent();

        assertFalse(first.isAlive());
        assertEquals(1, first.terminations);
        assertEquals(upgrade.id(), launcher.onlyProcess().launch().version());
        assertEquals(1, launcher.aliveCount());
    }

    @Test
    void storeActivationFailureDoesNotLaunchProcess() throws Exception {
        org.mockito.Mockito.doThrow(new com.audioviz.runtime.store.RuntimeStoreException(
            com.audioviz.runtime.store.RuntimeStoreException.FailureReason.IO_FAILURE
        )).when(store).activateCandidate(candidate);

        supervisor.start(candidate);
        runCurrent();

        assertEquals(FAILED, supervisor.snapshot().state());
        assertEquals("STORE_FAILURE", supervisor.snapshot().reasonCode());
        assertTrue(launcher.processes.isEmpty());
    }

    @Test
    void lifecycleValuesNeverRenderSecretsNoncesOrOwnedPaths() {
        start(candidate);
        FakeProcess process = launcher.onlyProcess();
        RuntimeLaunch launch = process.launch();
        RuntimeReady ready = ready(process);
        RuntimeHealth health = healthy(process, 1);

        assertFalse(launch.toString().contains("not-visible"));
        assertFalse(launch.toString().contains(NONCE));
        assertFalse(launch.toString().contains(candidate.root().toString()));
        assertFalse(ready.toString().contains(NONCE));
        assertFalse(health.toString().contains(NONCE));
        assertThrows(
            IllegalArgumentException.class,
            () -> new RuntimeReady("1.2.0", 1, 1, NONCE, 1, Set.of("capability-\u00e9"))
        );
    }

    private RuntimeSupervisor supervisor(RestartPolicy restartPolicy) {
        return new RuntimeSupervisor(
            store,
            launcher,
            (runtime, generation, nonce) -> new RuntimeLaunch(
                runtime.id(),
                runtime.entrypoint(),
                runtime.root(),
                runtime.root().resolve("state"),
                "0.0.0.0",
                9000,
                Map.of("MCAV_RENDERER_SECRET", "not-visible"),
                nonce,
                generation,
                runtime.state().runtimeApi()
            ),
            coordinator,
            clock,
            restartPolicy,
            () -> NONCE,
            new RuntimeSupervisor.Timings(
                READINESS_TIMEOUT,
                STABILITY_WINDOW,
                FAILURE_RESET,
                SHUTDOWN_GRACE,
                Duration.ofSeconds(2)
            ),
            event -> { }
        );
    }

    private void start(InstalledRuntime runtime) {
        supervisor.start(runtime);
        runCurrent();
        assertEquals(STARTING, supervisor.snapshot().state());
    }

    private void startAndStabilize(InstalledRuntime runtime) throws Exception {
        start(runtime);
        FakeProcess process = launcher.onlyProcess();
        supervisor.ready(ready(process));
        runCurrent();
        advance(STABILITY_WINDOW);
        verify(store).markHealthy(runtime);
    }

    private RuntimeReady ready(FakeProcess process) {
        RuntimeLaunch launch = process.launch();
        return new RuntimeReady(
            launch.version().releaseVersion(),
            launch.runtimeApi(),
            launch.generation(),
            launch.launchNonce(),
            process.pid(),
            Set.of("unified-ingress.v1")
        );
    }

    private RuntimeHealth healthy(FakeProcess process, long sequence) {
        RuntimeLaunch launch = process.launch();
        return new RuntimeHealth(
            launch.generation(), launch.launchNonce(), sequence, true, true, true, true, true,
            10, 0, 0
        );
    }

    private RuntimeHealth unhealthy(FakeProcess process, long sequence) {
        RuntimeLaunch launch = process.launch();
        return new RuntimeHealth(
            launch.generation(), launch.launchNonce(), sequence, true, true, false, true, true,
            10, 0, 0
        );
    }

    private InstalledRuntime runtime(String version, long generation, HealthState health) {
        RuntimeVersionId id = RuntimeVersionId.of(version, RuntimePlatform.LINUX_X86_64);
        Path root = Path.of("runtime-fixtures", id.directoryName()).toAbsolutePath();
        RuntimeState state = new RuntimeState(
            1, id, generation, DIGEST, DIGEST, 1, clock.instant(), health
        );
        return new InstalledRuntime(id, root, root.resolve("bin/audioviz-vj"), Map.of(), state);
    }

    private void runCurrent() {
        coordinator.runCurrent();
    }

    private void advance(Duration duration) {
        coordinator.advance(duration);
    }

    private static final class FakeLauncher implements RuntimeSupervisor.ProcessLauncher {
        private final List<FakeProcess> processes = new ArrayList<>();

        @Override
        public ManagedRuntimeProcess launch(RuntimeLaunch launch) {
            FakeProcess process = new FakeProcess(launch, 100 + processes.size());
            processes.add(process);
            return process;
        }

        FakeProcess onlyProcess() {
            return processes.get(processes.size() - 1);
        }

        long aliveCount() {
            return processes.stream().filter(FakeProcess::isAlive).count();
        }
    }

    private static final class FakeProcess implements ManagedRuntimeProcess {
        private final RuntimeLaunch launch;
        private final long pid;
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private boolean alive = true;
        private int shutdownRequests;
        private int terminations;

        FakeProcess(RuntimeLaunch launch, long pid) {
            this.launch = launch;
            this.pid = pid;
        }

        @Override
        public RuntimeLaunch launch() {
            return launch;
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public CompletableFuture<Integer> onExit() {
            return exit;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public void requestShutdown() {
            shutdownRequests++;
        }

        @Override
        public void terminate(Duration graceful, Duration normal) {
            terminations++;
            alive = false;
        }

        void exit(int code) {
            alive = false;
            exit.complete(code);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class ManualScheduledExecutor extends AbstractExecutorService
        implements ScheduledExecutorService {
        private final MutableClock clock;
        private final AtomicLong sequence = new AtomicLong();
        private final PriorityQueue<ManualTask<?>> tasks = new PriorityQueue<>(Comparator
            .comparing(ManualTask<?>::due)
            .thenComparingLong(ManualTask::sequence));
        private boolean shutdown;

        ManualScheduledExecutor(MutableClock clock) {
            this.clock = clock;
        }

        void runCurrent() {
            while (!tasks.isEmpty() && !tasks.peek().due().isAfter(clock.instant())) {
                tasks.remove().run();
            }
        }

        void advance(Duration duration) {
            Instant target = clock.instant().plus(duration);
            while (!tasks.isEmpty() && !tasks.peek().due().isAfter(target)) {
                ManualTask<?> task = tasks.remove();
                clock.advance(Duration.between(clock.instant(), task.due()));
                task.run();
            }
            clock.advance(Duration.between(clock.instant(), target));
            runCurrent();
        }

        @Override
        public void execute(Runnable command) {
            schedule(command, 0, TimeUnit.NANOSECONDS);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return enqueue(() -> {
                command.run();
                return null;
            }, delay, unit);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            return enqueue(callable, delay, unit);
        }

        private <V> ManualTask<V> enqueue(Callable<V> callable, long delay, TimeUnit unit) {
            if (shutdown) {
                throw new RejectedExecutionException("executor is shut down");
            }
            Instant due = clock.instant().plusNanos(unit.toNanos(delay));
            ManualTask<V> task = new ManualTask<>(callable, due, sequence.getAndIncrement());
            tasks.add(task);
            return task;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
            Runnable command, long initialDelay, long period, TimeUnit unit
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
            Runnable command, long initialDelay, long delay, TimeUnit unit
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> pending = new ArrayList<>();
            tasks.clear();
            return pending;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<Future<T>> invokeAll(
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit
        ) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ManualTask<V> implements ScheduledFuture<V> {
        private final Callable<V> callable;
        private final Instant due;
        private final long sequence;
        private final CompletableFuture<V> result = new CompletableFuture<>();

        ManualTask(Callable<V> callable, Instant due, long sequence) {
            this.callable = callable;
            this.due = due;
            this.sequence = sequence;
        }

        Instant due() {
            return due;
        }

        long sequence() {
            return sequence;
        }

        void run() {
            if (result.isCancelled()) {
                return;
            }
            try {
                result.complete(callable.call());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(Duration.between(Instant.now(), due).toNanos(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return result.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return result.isCancelled();
        }

        @Override
        public boolean isDone() {
            return result.isDone();
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            return result.get();
        }

        @Override
        public V get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
            return result.get(timeout, unit);
        }
    }
}
