package com.audioviz.runtime;

import com.audioviz.runtime.config.RuntimeConfig;
import com.audioviz.runtime.control.RuntimeControlChannel;
import com.audioviz.runtime.install.CancellationToken;
import com.audioviz.runtime.install.RuntimeInstaller;
import com.audioviz.runtime.release.ReleaseDescriptor;
import com.audioviz.runtime.release.RuntimePlatform;
import com.audioviz.runtime.store.RuntimeState;
import com.audioviz.runtime.store.RuntimeStore;
import com.audioviz.runtime.store.RuntimeStore.InstalledRuntime;
import com.audioviz.runtime.store.RuntimeVersionId;
import com.audioviz.runtime.supervisor.RuntimeHealth;
import com.audioviz.runtime.supervisor.RuntimeReady;
import com.audioviz.runtime.supervisor.RuntimeSupervisor;
import com.audioviz.runtime.supervisor.SupervisorState;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;

class PaperRuntimeManagerTest {
    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
    private static final String NONCE = "a".repeat(43);

    private RuntimeInstaller installer;
    private RuntimeStore store;
    private RuntimeSupervisor supervisor;
    private RuntimeControlChannel controlChannel;
    private ExecutorService coordinator;
    private ScheduledExecutorService supervisorScheduler;
    private PaperRuntimeManager manager;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void setUp() {
        installer = mock(RuntimeInstaller.class);
        store = mock(RuntimeStore.class);
        supervisor = mock(RuntimeSupervisor.class);
        controlChannel = mock(RuntimeControlChannel.class);
        coordinator = Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "AudioViz-Runtime-Coordinator"));
        supervisorScheduler = Executors.newSingleThreadScheduledExecutor(runnable ->
            new Thread(runnable, "AudioViz-Runtime-Scheduler"));
        manager = new PaperRuntimeManager(
            config(),
            descriptor(),
            RuntimePlatform.WINDOWS_X86_64,
            installer,
            store,
            supervisor,
            controlChannel,
            coordinator,
            supervisorScheduler,
            List.of(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            "renderer-secret"
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.stop();
        assertTrue(awaitNoRuntimeThreads());
    }

    @Test
    void startReturnsBeforeInstallationCompletesAndUsesCoordinatorThread() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> installThread = new AtomicReference<>();
        InstalledRuntime runtime = runtime("1.2.0", 12);
        when(installer.install(any(), any())).thenAnswer(invocation -> {
            installThread.set(Thread.currentThread().getName());
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new RuntimeInstaller.InstallResult(
                RuntimeInstaller.InstallOutcome.INSTALLED,
                runtime
            );
        });

        String callingThread = Thread.currentThread().getName();
        manager.start();

        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertNotEquals(callingThread, installThread.get());
        assertTrue(installThread.get().startsWith("AudioViz-Runtime-Coordinator"));
        release.countDown();
        verify(supervisor, timeout(2_000)).start(runtime);
    }

    @Test
    void concurrentChecksAreSerializedAndOnlyNewestResultStarts() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        InstalledRuntime runtime = runtime("1.2.0", 12);
        when(installer.install(any(), any())).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            int nowActive = active.incrementAndGet();
            maximumActive.accumulateAndGet(nowActive, Math::max);
            if (call == 1) {
                firstEntered.countDown();
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
            }
            active.decrementAndGet();
            return new RuntimeInstaller.InstallResult(
                RuntimeInstaller.InstallOutcome.REUSED_INSTALLED,
                runtime
            );
        });

        manager.check();
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
        manager.check();
        releaseFirst.countDown();

        verify(supervisor, timeout(2_000)).start(runtime);
        assertEquals(2, calls.get());
        assertEquals(1, maximumActive.get());
    }

    @Test
    void stopCancelsAndInvalidatesLateInstallResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        InstalledRuntime runtime = runtime("1.2.0", 12);
        when(installer.install(any(), any())).thenAnswer(invocation -> {
            CancellationToken token = invocation.getArgument(1);
            entered.countDown();
            while (!token.isCancelled()) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            cancelled.countDown();
            return new RuntimeInstaller.InstallResult(
                RuntimeInstaller.InstallOutcome.INSTALLED,
                runtime
            );
        });

        manager.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        manager.stop();

        assertTrue(cancelled.await(2, TimeUnit.SECONDS));
        verify(supervisor, never()).start(any());
        assertEquals("STOPPED", manager.status().reasonCode());
        assertFalse(manager.status().rendererConnected());
    }

    @Test
    void unchangedCheckDoesNotRestartRunningRuntime() throws Exception {
        InstalledRuntime runtime = runtime("1.2.0", 12);
        AtomicInteger calls = new AtomicInteger();
        when(installer.install(any(), any())).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            return new RuntimeInstaller.InstallResult(
                call == 1
                    ? RuntimeInstaller.InstallOutcome.INSTALLED
                    : RuntimeInstaller.InstallOutcome.REUSED_CURRENT,
                runtime
            );
        });
        manager.start();
        verify(supervisor, timeout(2_000)).start(runtime);
        clearInvocations(supervisor);

        manager.check();

        assertTrue(awaitStatusReason(manager, "UP_TO_DATE"));
        verify(supervisor, never()).start(any());
        assertEquals(runtime.id(), manager.status().activeVersion().orElseThrow());
    }

    @Test
    void authenticatedSignalsOnlyPublishAfterSupervisorAcceptance() {
        RuntimeReady ready = new RuntimeReady("1.2.0", 1, 4, NONCE, 42, Set.of("health"));
        RuntimeHealth health = new RuntimeHealth(
            4,
            NONCE,
            7,
            true,
            true,
            true,
            true,
            true,
            25,
            3,
            4
        );

        RuntimeStatusSnapshot before = manager.status();
        manager.onRuntimeReady(ready);
        manager.onRuntimeHealth(health);

        verify(supervisor).ready(ready);
        verify(supervisor).health(health);
        RuntimeStatusSnapshot status = manager.status();
        assertFalse(status.rendererConnected());
        assertEquals(-1, status.lastHealthSequence());
        assertEquals(0, status.ingressQueueDepth());
        assertEquals(0, status.renderQueueDepth());
        assertFalse(before.rendererConnected());
        assertEquals(-1, before.lastHealthSequence());

        when(supervisor.snapshot()).thenReturn(new RuntimeSupervisor.SupervisorSnapshot(
            SupervisorState.READY,
            4,
            Optional.empty(),
            false,
            true,
            true,
            false,
            0,
            0,
            "HEALTHY",
            NOW,
            Optional.empty(),
            Optional.empty(),
            7,
            0
        ));
        manager.onSupervisorEvent(new RuntimeSupervisor.SupervisorEvent(
            SupervisorState.READY,
            4,
            "HEALTHY",
            NOW,
            Optional.of(new RuntimeSupervisor.AcceptedHealth(4, 7, true, 25, 3, 4))
        ));

        status = manager.status();
        assertTrue(status.rendererConnected());
        assertEquals(7, status.lastHealthSequence());
        assertEquals(3, status.ingressQueueDepth());
        assertEquals(4, status.renderQueueDepth());

        manager.onRendererDisconnect(4);

        verify(supervisor).rendererDisconnected(4);
        assertTrue(manager.status().rendererConnected());
    }

    @Test
    void stopCannotBeOvertakenByCoordinatorStart() throws Exception {
        manager.stop();
        coordinator = Executors.newSingleThreadExecutor();
        supervisorScheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch recoverEntered = new CountDownLatch(1);
        CountDownLatch releaseRecover = new CountDownLatch(1);
        CountDownLatch stopQueued = new CountDownLatch(1);
        InstalledRuntime runtime = runtime("1.2.0", 12);
        doAnswer(invocation -> {
            recoverEntered.countDown();
            while (releaseRecover.getCount() > 0) {
                try {
                    releaseRecover.await();
                } catch (InterruptedException ignored) {
                    // Keep the coordinator action in flight until stop is queued.
                }
            }
            return null;
        }).when(store).recover();
        when(store.current()).thenReturn(Optional.of(runtime));
        doAnswer(invocation -> {
            stopQueued.countDown();
            return null;
        }).when(supervisor).stop(any());
        manager = new PaperRuntimeManager(
            config(RuntimeConfig.ReleaseChannel.STABLE, false),
            descriptor(),
            RuntimePlatform.WINDOWS_X86_64,
            installer,
            store,
            supervisor,
            controlChannel,
            coordinator,
            supervisorScheduler,
            List.of(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            "renderer-secret"
        );
        manager.start();
        assertTrue(recoverEntered.await(2, TimeUnit.SECONDS));

        Thread stopper = new Thread(manager::stop);
        stopper.start();
        assertTrue(stopQueued.await(2, TimeUnit.SECONDS));
        releaseRecover.countDown();
        stopper.join(2_000);

        assertFalse(stopper.isAlive());
        verify(supervisor, never()).start(any());
        assertEquals("STOPPED", manager.status().reasonCode());
    }

    @Test
    void shutdownUsesOneDeadlineAcrossAllExecutors() throws Exception {
        manager.stop();
        coordinator = Executors.newSingleThreadExecutor();
        supervisorScheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch blockersEntered = new CountDownLatch(3);
        CountDownLatch releaseBlockers = new CountDownLatch(1);
        List<ExecutorService> blockers = List.of(
            Executors.newSingleThreadExecutor(),
            Executors.newSingleThreadExecutor(),
            Executors.newSingleThreadExecutor()
        );
        Runnable blockingTask = () -> {
            blockersEntered.countDown();
            while (releaseBlockers.getCount() > 0) {
                try {
                    releaseBlockers.await();
                } catch (InterruptedException ignored) {
                    // Deliberately resist shutdown to exercise the shared deadline.
                }
            }
        };
        blockers.forEach(executor -> executor.execute(blockingTask));
        assertTrue(blockersEntered.await(2, TimeUnit.SECONDS));
        Duration deadline = Duration.ofMillis(300);
        manager = new PaperRuntimeManager(
            config(),
            descriptor(),
            RuntimePlatform.WINDOWS_X86_64,
            installer,
            store,
            supervisor,
            controlChannel,
            coordinator,
            supervisorScheduler,
            blockers,
            Clock.fixed(NOW, ZoneOffset.UTC),
            "renderer-secret",
            deadline
        );

        long startedAt = System.nanoTime();
        manager.stop();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        releaseBlockers.countDown();
        for (ExecutorService blocker : blockers) {
            assertTrue(blocker.awaitTermination(2, TimeUnit.SECONDS));
        }

        assertTrue(elapsedMillis < 700, "shutdown took " + elapsedMillis + "ms");
        assertEquals("STOPPED", manager.status().reasonCode());
    }

    @Test
    void stoppedManagerRejectsLateLifecycleWorkAndSignals() throws Exception {
        RuntimeVersionId rollbackTarget = runtime("1.1.0", 11).id();
        RuntimeReady ready = new RuntimeReady("1.2.0", 1, 4, NONCE, 42, Set.of("health"));
        RuntimeHealth health = new RuntimeHealth(
            4,
            NONCE,
            7,
            true,
            true,
            true,
            true,
            true,
            25,
            3,
            4
        );

        manager.stop();
        manager.check();
        manager.retry();
        assertFalse(manager.rollback(rollbackTarget));
        manager.onRuntimeReady(ready);
        manager.onRuntimeHealth(health);
        manager.onRendererDisconnect(4);

        verify(installer, never()).install(any(), any());
        verify(supervisor, never()).retry();
        verify(supervisor, never()).rollback();
        verify(supervisor, never()).ready(any());
        verify(supervisor, never()).health(any());
        verify(supervisor, never()).rendererDisconnected(4);
        assertEquals("STOPPED", manager.status().reasonCode());
    }

    @Test
    void betaChannelUsesBetaManifestPath() throws Exception {
        RuntimeInstaller betaInstaller = mock(RuntimeInstaller.class);
        RuntimeSupervisor betaSupervisor = mock(RuntimeSupervisor.class);
        RuntimeControlChannel betaControl = mock(RuntimeControlChannel.class);
        ExecutorService betaCoordinator = Executors.newSingleThreadExecutor();
        ScheduledExecutorService betaScheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicReference<RuntimeInstaller.InstallRequest> request = new AtomicReference<>();
        InstalledRuntime runtime = runtime("1.3.0-beta.1", 13);
        when(betaInstaller.install(any(), any())).thenAnswer(invocation -> {
            request.set(invocation.getArgument(0));
            return new RuntimeInstaller.InstallResult(
                RuntimeInstaller.InstallOutcome.INSTALLED,
                runtime
            );
        });
        PaperRuntimeManager betaManager = new PaperRuntimeManager(
            config(RuntimeConfig.ReleaseChannel.BETA),
            descriptor(),
            RuntimePlatform.WINDOWS_X86_64,
            betaInstaller,
            store,
            betaSupervisor,
            betaControl,
            betaCoordinator,
            betaScheduler,
            List.of(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            "renderer-secret"
        );
        try {
            betaManager.check();

            verify(betaSupervisor, timeout(2_000)).start(runtime);
            assertEquals(
                URI.create("https://releases.mcav.live/runtime/beta/manifest.json"),
                request.get().descriptor().manifestUri()
            );
        } finally {
            betaManager.stop();
        }
    }

    @Test
    void productionFactoryPersistsStrongRendererCredentialWithoutPublishingIt() throws Exception {
        Logger logger = Logger.getLogger(getClass().getName());
        PaperRuntimeManager first = PaperRuntimeManager.create(
            config(),
            descriptor(),
            temporaryDirectory,
            "127.0.0.1",
            8765,
            "",
            logger
        );
        String credential = first.rendererAuthenticationToken();
        try {
            assertEquals(43, credential.length());
            assertTrue(first.runtimeControlHandler().isPresent());
            assertFalse(first.status().toString().contains(credential));
        } finally {
            first.stop();
        }

        PaperRuntimeManager second = PaperRuntimeManager.create(
            config(),
            descriptor(),
            temporaryDirectory,
            "127.0.0.1",
            8765,
            "",
            logger
        );
        try {
            assertEquals(credential, second.rendererAuthenticationToken());
        } finally {
            second.stop();
        }
    }

    @Test
    void productionFactoryRejectsRendererAddressOutsideValidatedConfiguration() {
        PaperRuntimeManager.InitializationException error = assertThrows(
            PaperRuntimeManager.InitializationException.class,
            () -> PaperRuntimeManager.create(
                config(),
                descriptor(),
                temporaryDirectory,
                "localhost",
                8765,
                "",
                Logger.getLogger(getClass().getName())
            )
        );

        assertEquals(PaperRuntimeManager.InitializationFailure.INVALID_RENDERER, error.reason());
    }

    @Test
    void productionFactoryFailsClosedWhenSelectedChannelIsUnavailable() {
        ReleaseDescriptor unavailable = new ReleaseDescriptor(
            URI.create("https://releases.mcav.live/runtime/manifest.json"),
            Map.of(),
            Set.of("releases.mcav.live"),
            1,
            1
        );

        PaperRuntimeManager.InitializationException error = assertThrows(
            PaperRuntimeManager.InitializationException.class,
            () -> PaperRuntimeManager.create(
                config(RuntimeConfig.ReleaseChannel.BETA),
                unavailable,
                temporaryDirectory,
                "127.0.0.1",
                8765,
                "",
                Logger.getLogger(getClass().getName())
            )
        );

        assertEquals(PaperRuntimeManager.InitializationFailure.CHANNEL_UNAVAILABLE, error.reason());
    }

    private static RuntimeConfig config() {
        return config(RuntimeConfig.ReleaseChannel.STABLE);
    }

    private static RuntimeConfig config(RuntimeConfig.ReleaseChannel channel) {
        return config(channel, true);
    }

    private static RuntimeConfig config(
        RuntimeConfig.ReleaseChannel channel,
        boolean installOnStart
    ) {
        return new RuntimeConfig(
            true,
            channel,
            installOnStart,
            "0.0.0.0",
            8080,
            Optional.empty(),
            Duration.ofSeconds(30),
            RuntimeConfig.ResourceProfile.AUTO,
            3,
            new RuntimeConfig.TlsConfig(
                RuntimeConfig.TlsMode.GENERATED,
                Optional.empty(),
                Optional.empty()
            ),
            new RuntimeConfig.PerformanceConfig(
                RuntimeConfig.PerformanceProfile.BALANCED,
                160,
                20,
                true
            ),
            "127.0.0.1"
        );
    }

    private static ReleaseDescriptor descriptor() {
        return new ReleaseDescriptor(
            URI.create("https://releases.mcav.live/runtime/stable/manifest.json"),
            Map.of(),
            Set.of("releases.mcav.live"),
            1,
            1
        );
    }

    private static InstalledRuntime runtime(String version, long generation) {
        RuntimeVersionId id = RuntimeVersionId.of(version, RuntimePlatform.WINDOWS_X86_64);
        RuntimeState state = new RuntimeState(
            1,
            id,
            generation,
            "a".repeat(64),
            "b".repeat(64),
            1,
            NOW,
            RuntimeState.HealthState.CANDIDATE
        );
        Path root = Path.of("runtime", version).toAbsolutePath();
        return new InstalledRuntime(id, root, root.resolve("audioviz-vj.exe"), Map.of(), state);
    }

    private static boolean awaitNoRuntimeThreads() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            boolean found = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.getName().startsWith(
                    "AudioViz-Runtime-"
                ));
            if (!found) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static boolean awaitStatusReason(PaperRuntimeManager manager, String reason)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (reason.equals(manager.status().reasonCode())) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }
}
