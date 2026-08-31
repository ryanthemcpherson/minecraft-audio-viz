package com.audioviz.runtime;

import com.audioviz.runtime.config.RuntimeConfig;
import com.audioviz.runtime.control.RuntimeControlChannel;
import com.audioviz.runtime.control.RuntimeControlMessageHandler;
import com.audioviz.runtime.install.CancellationToken;
import com.audioviz.runtime.install.RuntimeInstallEvent;
import com.audioviz.runtime.install.RuntimeInstaller;
import com.audioviz.runtime.net.RuntimeHttpSource;
import com.audioviz.runtime.release.ReleaseDescriptor;
import com.audioviz.runtime.release.RuntimeManifestVerifier;
import com.audioviz.runtime.release.RuntimePlatform;
import com.audioviz.runtime.store.RuntimeArchiveVerifier;
import com.audioviz.runtime.store.RuntimePaths;
import com.audioviz.runtime.store.RuntimeStore;
import com.audioviz.runtime.store.RuntimeStore.InstalledRuntime;
import com.audioviz.runtime.store.RuntimeStoreException;
import com.audioviz.runtime.store.RuntimeVersionId;
import com.audioviz.runtime.supervisor.RestartPolicy;
import com.audioviz.runtime.supervisor.RuntimeHealth;
import com.audioviz.runtime.supervisor.RuntimeLaunch;
import com.audioviz.runtime.supervisor.RuntimeProcessLauncher;
import com.audioviz.runtime.supervisor.RuntimeReady;
import com.audioviz.runtime.supervisor.RuntimeSupervisor;
import com.audioviz.runtime.supervisor.RuntimeSupervisor.SupervisorEvent;
import com.audioviz.runtime.supervisor.SupervisorState;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PaperRuntimeManager {
    private static final Duration STABILITY_WINDOW = Duration.ofSeconds(60);
    private static final Duration FAILURE_RESET_WINDOW = Duration.ofMinutes(10);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);
    private static final Duration NORMAL_TERMINATION = Duration.ofSeconds(2);
    private static final Duration EXECUTOR_STOP_TIMEOUT = Duration.ofSeconds(10);
    private static final int SECRET_BYTES = 32;
    private static final int MINIMUM_SECRET_CHARACTERS = 32;
    private static final int MAXIMUM_SECRET_CHARACTERS = 1_024;
    private static final String SECRET_FILE = "renderer-auth.token";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RuntimeConfig config;
    private final ReleaseDescriptor descriptor;
    private final RuntimePlatform platform;
    private final RuntimeInstaller installer;
    private final RuntimeStore store;
    private final RuntimeSupervisor supervisor;
    private final RuntimeControlChannel controlChannel;
    private final RuntimeControlMessageHandler controlHandler;
    private final ExecutorService coordinator;
    private final ScheduledExecutorService supervisorScheduler;
    private final List<ExecutorService> auxiliaryExecutors;
    private final Clock clock;
    private final Logger logger;
    private final String rendererSecret;
    private final Duration executorStopTimeout;
    private final boolean enabled;
    private final AtomicReference<RuntimeStatusSnapshot> published;
    private final AtomicReference<SupervisorState> lifecycleState = new AtomicReference<>(
        SupervisorState.DISABLED
    );
    private final AtomicReference<CancellationToken> activeInstall = new AtomicReference<>();
    private final AtomicLong operationGeneration = new AtomicLong();
    private final AtomicLong expectedControlGeneration = new AtomicLong();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Object lifecycleGate = new Object();

    PaperRuntimeManager(
        RuntimeConfig config,
        ReleaseDescriptor descriptor,
        RuntimePlatform platform,
        RuntimeInstaller installer,
        RuntimeStore store,
        RuntimeSupervisor supervisor,
        RuntimeControlChannel controlChannel,
        ExecutorService coordinator,
        ScheduledExecutorService supervisorScheduler,
        List<ExecutorService> auxiliaryExecutors,
        Clock clock,
        String rendererSecret
    ) {
        this(
            config,
            descriptorForChannel(config, descriptor),
            platform,
            installer,
            store,
            supervisor,
            controlChannel,
            coordinator,
            supervisorScheduler,
            auxiliaryExecutors,
            clock,
            rendererSecret,
            EXECUTOR_STOP_TIMEOUT,
            Logger.getLogger(PaperRuntimeManager.class.getName()),
            true,
            "INITIALIZED"
        );
    }

    PaperRuntimeManager(
        RuntimeConfig config,
        ReleaseDescriptor descriptor,
        RuntimePlatform platform,
        RuntimeInstaller installer,
        RuntimeStore store,
        RuntimeSupervisor supervisor,
        RuntimeControlChannel controlChannel,
        ExecutorService coordinator,
        ScheduledExecutorService supervisorScheduler,
        List<ExecutorService> auxiliaryExecutors,
        Clock clock,
        String rendererSecret,
        Duration executorStopTimeout
    ) {
        this(
            config,
            descriptorForChannel(config, descriptor),
            platform,
            installer,
            store,
            supervisor,
            controlChannel,
            coordinator,
            supervisorScheduler,
            auxiliaryExecutors,
            clock,
            rendererSecret,
            executorStopTimeout,
            Logger.getLogger(PaperRuntimeManager.class.getName()),
            true,
            "INITIALIZED"
        );
    }

    private PaperRuntimeManager(
        RuntimeConfig config,
        ReleaseDescriptor descriptor,
        RuntimePlatform platform,
        RuntimeInstaller installer,
        RuntimeStore store,
        RuntimeSupervisor supervisor,
        RuntimeControlChannel controlChannel,
        ExecutorService coordinator,
        ScheduledExecutorService supervisorScheduler,
        List<ExecutorService> auxiliaryExecutors,
        Clock clock,
        String rendererSecret,
        Duration executorStopTimeout,
        Logger logger,
        boolean enabled,
        String initialReason
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.descriptor = descriptor;
        this.platform = platform;
        this.installer = installer;
        this.store = store;
        this.supervisor = supervisor;
        this.controlChannel = controlChannel;
        this.controlHandler = controlChannel == null
            ? null
            : new RuntimeControlMessageHandler(controlChannel);
        this.coordinator = coordinator;
        this.supervisorScheduler = supervisorScheduler;
        this.auxiliaryExecutors = List.copyOf(auxiliaryExecutors);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.rendererSecret = Objects.requireNonNull(rendererSecret, "rendererSecret");
        this.executorStopTimeout = requirePositive(executorStopTimeout, "executorStopTimeout");
        this.enabled = enabled;
        int runtimeApi = descriptor == null ? 1 : descriptor.runtimeApi();
        published = new AtomicReference<>(new RuntimeStatusSnapshot(
            SupervisorState.DISABLED,
            Optional.empty(),
            Optional.empty(),
            runtimeApi,
            clock.instant(),
            config.publicHost(),
            config.publicPort(),
            initialReason,
            Optional.empty(),
            false,
            -1,
            0,
            0,
            0,
            0
        ));
    }

    public static PaperRuntimeManager create(
        RuntimeConfig config,
        ReleaseDescriptor descriptor,
        Path pluginDataDirectory,
        String rendererHost,
        int rendererPort,
        String configuredRendererSecret,
        Logger logger
    ) throws InitializationException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory");
        Objects.requireNonNull(rendererHost, "rendererHost");
        Objects.requireNonNull(logger, "logger");
        String normalizedRendererHost = rendererHost.strip();
        if (!config.enabled()) {
            return disabled(config, "CONFIG_DISABLED", configuredRendererSecret, logger);
        }
        if (
            rendererPort <= 0 || rendererPort > 65_535 ||
            !normalizedRendererHost.equals(config.rendererAddress())
        ) {
            throw new InitializationException(InitializationFailure.INVALID_RENDERER);
        }
        ReleaseDescriptor selectedDescriptor;
        try {
            selectedDescriptor = descriptorForChannel(config, descriptor);
        } catch (IllegalArgumentException error) {
            throw new InitializationException(InitializationFailure.CHANNEL_UNAVAILABLE, error);
        }

        ExecutorService coordinator = null;
        ScheduledThreadPoolExecutor scheduler = null;
        ExecutorService networkExecutor = null;
        ExecutorService logExecutor = null;
        try {
            RuntimeLimits limits = RuntimeLimits.releaseDefaults();
            Clock clock = Clock.systemUTC();
            RuntimePaths paths = RuntimePaths.create(
                pluginDataDirectory.toAbsolutePath().normalize().resolve("runtime")
            );
            String rendererSecret = rendererSecret(
                configuredRendererSecret,
                paths.state().resolve(SECRET_FILE)
            );
            RuntimePlatform platform = RuntimePlatform.detect(
                System.getProperty("os.name"),
                System.getProperty("os.arch")
            );

            coordinator = Executors.newSingleThreadExecutor(
                namedThreadFactory("AudioViz-Runtime-Coordinator")
            );
            scheduler = new ScheduledThreadPoolExecutor(
                1,
                namedThreadFactory("AudioViz-Runtime-Scheduler")
            );
            scheduler.setRemoveOnCancelPolicy(true);
            networkExecutor = Executors.newSingleThreadExecutor(
                namedThreadFactory("AudioViz-Runtime-Network")
            );
            logExecutor = Executors.newFixedThreadPool(
                2,
                namedThreadFactory("AudioViz-Runtime-Log")
            );

            RuntimeStore store = new RuntimeStore(
                paths,
                limits,
                clock,
                boundary -> { }
            );
            CallbackBridge callbacks = new CallbackBridge();
            RuntimeControlChannel controlChannel = new RuntimeControlChannel(
                callbacks::ready,
                callbacks::health,
                callbacks::disconnected
            );
            RuntimeHttpSource httpSource = new RuntimeHttpSource(
                networkExecutor,
                paths.downloads()
            );
            RuntimeInstaller installer = new RuntimeInstaller(
                paths,
                store,
                limits,
                clock,
                httpSource,
                new RuntimeManifestVerifier(limits),
                new RuntimeArchiveVerifier(limits, paths),
                callbacks::installEvent
            );
            RuntimeProcessLauncher processLauncher = new RuntimeProcessLauncher(
                logExecutor,
                generation -> controlChannel.sendShutdown(generation, "PLUGIN_SHUTDOWN"),
                (stream, line) -> logger.info("Managed VJ " + stream.name() + ": " + line)
            );
            RuntimeSupervisor supervisor = new RuntimeSupervisor(
                store,
                processLauncher,
                callbacks::launch,
                scheduler,
                clock,
                new RestartPolicy(() -> SECURE_RANDOM.nextDouble(0.8, 1.2)),
                PaperRuntimeManager::newNonce,
                new RuntimeSupervisor.Timings(
                    config.readinessTimeout(),
                    STABILITY_WINDOW,
                    FAILURE_RESET_WINDOW,
                    SHUTDOWN_GRACE,
                    NORMAL_TERMINATION
                ),
                callbacks::supervisorEvent
            );
            PaperRuntimeManager manager = new PaperRuntimeManager(
                config,
                selectedDescriptor,
                platform,
                installer,
                store,
                supervisor,
                controlChannel,
                coordinator,
                scheduler,
                List.of(networkExecutor, logExecutor),
                clock,
                rendererSecret,
                EXECUTOR_STOP_TIMEOUT,
                logger,
                true,
                "INITIALIZED"
            );
            callbacks.bind(manager, paths, normalizedRendererHost, rendererPort);
            return manager;
        } catch (RuntimeStoreException error) {
            shutdownCreated(coordinator, scheduler, networkExecutor, logExecutor);
            throw new InitializationException(InitializationFailure.STORE_FAILURE, error);
        } catch (UnsupportedOperationException error) {
            shutdownCreated(coordinator, scheduler, networkExecutor, logExecutor);
            throw new InitializationException(InitializationFailure.UNSUPPORTED_PLATFORM, error);
        } catch (IOException | IllegalArgumentException | SecurityException error) {
            shutdownCreated(coordinator, scheduler, networkExecutor, logExecutor);
            throw new InitializationException(InitializationFailure.INITIALIZATION_FAILED, error);
        }
    }

    public static PaperRuntimeManager disabled(
        RuntimeConfig config,
        String reasonCode,
        String configuredRendererSecret,
        Logger logger
    ) {
        return new PaperRuntimeManager(
            config,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            Clock.systemUTC(),
            configuredRendererSecret == null ? "" : configuredRendererSecret.strip(),
            EXECUTOR_STOP_TIMEOUT,
            logger,
            false,
            safeReason(reasonCode)
        );
    }

    public void start() {
        synchronized (lifecycleGate) {
            if (!enabled || stopped.get() || !started.compareAndSet(false, true)) {
                return;
            }
            if (config.installOnStart()) {
                scheduleInstallLocked();
            } else {
                submitCoordinator(this::startInstalledRuntime);
            }
        }
    }

    public void check() {
        synchronized (lifecycleGate) {
            if (!enabled || stopped.get()) {
                return;
            }
            started.set(true);
            scheduleInstallLocked();
        }
    }

    public void retry() {
        submitCoordinator(this::retryOnCoordinator);
    }

    public boolean rollback(RuntimeVersionId expectedTarget) {
        Objects.requireNonNull(expectedTarget, "expectedTarget");
        if (!enabled || stopped.get()) {
            return false;
        }
        submitCoordinator(() -> rollbackOnCoordinator(expectedTarget));
        return true;
    }

    public void onRuntimeReady(RuntimeReady ready) {
        Objects.requireNonNull(ready, "ready");
        synchronized (lifecycleGate) {
            if (!enabled || stopped.get()) {
                return;
            }
            supervisor.ready(ready);
        }
    }

    public void onRuntimeHealth(RuntimeHealth health) {
        Objects.requireNonNull(health, "health");
        synchronized (lifecycleGate) {
            if (!enabled || stopped.get()) {
                return;
            }
            supervisor.health(health);
        }
    }

    public void onRendererDisconnect(long launchGeneration) {
        synchronized (lifecycleGate) {
            if (!enabled || stopped.get()) {
                return;
            }
            supervisor.rendererDisconnected(launchGeneration);
        }
    }

    public RuntimeStatusSnapshot status() {
        return published.get();
    }

    public Optional<RuntimeControlMessageHandler> runtimeControlHandler() {
        return Optional.ofNullable(controlHandler);
    }

    public String rendererAuthenticationToken() {
        return rendererSecret;
    }

    public void stop() {
        long stopDeadline = System.nanoTime() + executorStopTimeout.toNanos();
        synchronized (lifecycleGate) {
            if (!enabled || !stopped.compareAndSet(false, true)) {
                return;
            }
            operationGeneration.incrementAndGet();
            CancellationToken token = activeInstall.getAndSet(null);
            if (token != null) {
                token.cancel();
            }
            supervisor.stop(SHUTDOWN_GRACE);
        }

        boolean cleanStop = awaitSupervisorStop(stopDeadline);
        clearExpectedControl();

        coordinator.shutdownNow();
        supervisorScheduler.shutdownNow();
        for (ExecutorService executor : auxiliaryExecutors) {
            executor.shutdownNow();
        }
        cleanStop &= awaitTermination(coordinator, stopDeadline);
        cleanStop &= awaitTermination(supervisorScheduler, stopDeadline);
        for (ExecutorService executor : auxiliaryExecutors) {
            cleanStop &= awaitTermination(executor, stopDeadline);
        }
        if (!cleanStop) {
            logger.log(Level.WARNING, "Managed VJ shutdown incomplete: EXECUTOR_STOP_TIMEOUT");
        }
        lifecycleState.set(SupervisorState.DISABLED);
        published.updateAndGet(previous -> copyStatus(
                previous,
                SupervisorState.DISABLED,
                previous.activeVersion(),
                previous.lastKnownGoodVersion(),
                "STOPPED",
                Optional.empty(),
                false,
                previous.lastHealthSequence(),
                previous.lastRenderAgeMillis(),
                previous.ingressQueueDepth(),
                previous.renderQueueDepth(),
                previous.rejectedSignals()
            ));
    }

    private void scheduleInstallLocked() {
        if (stopped.get()) {
            return;
        }
        long generation = operationGeneration.incrementAndGet();
        CancellationToken token = new CancellationToken(generation);
        CancellationToken previous = activeInstall.getAndSet(token);
        if (previous != null) {
            previous.cancel();
        }
        publishInstallState(SupervisorState.CHECKING, "CHECKING");
        submitCoordinator(() -> installAndStart(generation, token));
    }

    private void installAndStart(long generation, CancellationToken token) {
        try {
            RuntimeInstaller.InstallResult result = installer.install(
                new RuntimeInstaller.InstallRequest(
                    descriptor,
                    platform,
                    descriptor.runtimeApi(),
                    generation
                ),
                token
            );
            if (!isCurrent(generation, token) || result.outcome() == RuntimeInstaller.InstallOutcome.CANCELLED) {
                return;
            }
            InstalledRuntime runtime = result.runtime().orElseThrow();
            if (
                result.outcome() == RuntimeInstaller.InstallOutcome.REUSED_CURRENT &&
                runtimeAlreadyActive(runtime)
            ) {
                publishUpToDate(runtime);
                return;
            }
            try {
                store.prune(config.retainVersions(), Set.of(runtime.id()));
            } catch (RuntimeStoreException error) {
                logger.log(Level.WARNING, "Managed VJ retention failed: " + error.reason().name());
            }
            if (!isCurrent(generation, token)) {
                return;
            }
            startSupervisor(runtime, generation, token);
        } catch (RuntimeInstaller.InstallException error) {
            if (isCurrent(generation, token)) {
                publishFailure("INSTALL_" + error.reason().name());
            }
        } catch (RuntimeException error) {
            if (isCurrent(generation, token)) {
                publishFailure("INSTALL_INTERNAL_FAILURE");
                logger.log(Level.WARNING, "Managed VJ install failed: INSTALL_INTERNAL_FAILURE");
            }
        } finally {
            activeInstall.compareAndSet(token, null);
        }
    }

    private void startInstalledRuntime() {
        if (stopped.get()) {
            return;
        }
        try {
            store.recover();
            Optional<InstalledRuntime> runtime = store.current()
                .filter(value -> value.state().runtimeApi() == descriptor.runtimeApi());
            if (runtime.isEmpty()) {
                runtime = store.findCompatible(descriptor.runtimeApi());
            }
            if (runtime.isEmpty()) {
                publishFailure("NO_RUNTIME");
                return;
            }
            InstalledRuntime selected = runtime.orElseThrow();
            startSupervisor(selected);
        } catch (RuntimeStoreException error) {
            publishFailure("STORE_FAILURE");
        }
    }

    private void rollbackOnCoordinator(RuntimeVersionId expectedTarget) {
        if (stopped.get()) {
            return;
        }
        try {
            Optional<InstalledRuntime> target = store.rollbackTarget(descriptor.runtimeApi());
            if (target.isEmpty()) {
                publishFailure("NO_ROLLBACK");
                return;
            }
            if (!target.orElseThrow().id().equals(expectedTarget)) {
                publishFailure("ROLLBACK_TARGET_MISMATCH");
                return;
            }
            rollbackSupervisor();
        } catch (RuntimeStoreException error) {
            publishFailure("STORE_FAILURE");
        }
    }

    private void startSupervisor(
        InstalledRuntime runtime,
        long generation,
        CancellationToken token
    ) {
        synchronized (lifecycleGate) {
            if (!isCurrent(generation, token)) {
                return;
            }
            publishStarting(runtime);
            supervisor.start(runtime);
        }
    }

    private void startSupervisor(InstalledRuntime runtime) {
        synchronized (lifecycleGate) {
            if (stopped.get()) {
                return;
            }
            publishStarting(runtime);
            supervisor.start(runtime);
        }
    }

    private void retryOnCoordinator() {
        synchronized (lifecycleGate) {
            if (!stopped.get()) {
                supervisor.retry();
            }
        }
    }

    private void rollbackSupervisor() {
        synchronized (lifecycleGate) {
            if (!stopped.get()) {
                supervisor.rollback();
            }
        }
    }

    private RuntimeLaunch createLaunch(
        InstalledRuntime runtime,
        long generation,
        String nonce,
        RuntimePaths paths,
        String rendererHost,
        int rendererPort
    ) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("MCAV_RENDERER_URL", rendererUrl(rendererHost, rendererPort));
        environment.put("MCAV_RENDERER_TOKEN", rendererSecret);
        environment.put("MCAV_LAUNCH_NONCE", nonce);
        environment.put("MCAV_LAUNCH_GENERATION", Long.toString(generation));
        environment.put("MCAV_RUNTIME_API", Integer.toString(descriptor.runtimeApi()));
        environment.put("MCAV_RELEASE_VERSION", runtime.id().releaseVersion());
        environment.put("MCAV_RESOURCE_PROFILE", config.resourceProfile().name());
        environment.put("MCAV_PERFORMANCE_PROFILE", config.performance().profile().name());
        environment.put("MCAV_ENTITY_BUDGET", Integer.toString(config.performance().entityBudget()));
        environment.put("MCAV_TARGET_RENDER_FPS", Integer.toString(
            config.performance().targetRenderFps()
        ));
        environment.put("MCAV_TLS_MODE", config.tls().mode().name());
        config.publicUrl().map(URI::toString).ifPresent(value ->
            environment.put("MCAV_PUBLIC_URL", value));
        config.tls().certificate().ifPresent(value ->
            environment.put("MCAV_TLS_CERTIFICATE", value.toString()));
        config.tls().privateKey().ifPresent(value ->
            environment.put("MCAV_TLS_PRIVATE_KEY", value.toString()));

        controlChannel.expect(new RuntimeControlChannel.ExpectedRuntime(
            generation,
            nonce,
            runtime.id().releaseVersion(),
            descriptor.runtimeApi()
        ));
        expectedControlGeneration.set(generation);
        return new RuntimeLaunch(
            runtime.id(),
            runtime.entrypoint(),
            runtime.root(),
            paths.state(),
            config.publicHost(),
            config.publicPort(),
            environment,
            nonce,
            generation,
            descriptor.runtimeApi()
        );
    }

    private void onInstallEvent(RuntimeInstallEvent event) {
        if (stopped.get() || event.generation() != operationGeneration.get()) {
            return;
        }
        SupervisorState state = switch (event.stage()) {
            case CHECKING, INSTALLED -> SupervisorState.CHECKING;
            case DOWNLOADING -> SupervisorState.DOWNLOADING;
            case VERIFYING -> SupervisorState.VERIFYING;
            case STAGING -> SupervisorState.STAGING;
        };
        publishInstallState(state, event.code().toUpperCase(Locale.ROOT));
    }

    void onSupervisorEvent(SupervisorEvent event) {
        synchronized (lifecycleGate) {
            if (stopped.get() && event.state() != SupervisorState.DISABLED) {
                return;
            }
            RuntimeSupervisor.SupervisorSnapshot supervisorStatus = supervisor.snapshot();
            lifecycleState.set(event.state());
            if (
                event.state() != SupervisorState.READY &&
                event.state() != SupervisorState.DEGRADED &&
                event.state() != SupervisorState.STOPPING
            ) {
                clearExpectedControl();
            }
            RuntimeSupervisor.AcceptedHealth acceptedHealth = event.acceptedHealth().orElse(null);
            Optional<RuntimeVersionId> stableRollback = "STABLE".equals(event.reasonCode())
                ? rollbackTargetId()
                : Optional.empty();
            published.updateAndGet(previous -> {
                if (stopped.get() && event.state() != SupervisorState.DISABLED) {
                    return previous;
                }
                boolean rendererConnected = previous.rendererConnected();
                long healthSequence = previous.lastHealthSequence();
                long renderAge = previous.lastRenderAgeMillis();
                int ingressDepth = previous.ingressQueueDepth();
                int renderDepth = previous.renderQueueDepth();
                if (event.state() == SupervisorState.STARTING) {
                    rendererConnected = false;
                    healthSequence = -1;
                    renderAge = 0;
                    ingressDepth = 0;
                    renderDepth = 0;
                } else if (
                    acceptedHealth != null &&
                    isHealthEvent(event.reasonCode()) &&
                    acceptedHealth.generation() == supervisorStatus.generation() &&
                    acceptedHealth.sequence() == supervisorStatus.lastHealthSequence()
                ) {
                    rendererConnected = acceptedHealth.rendererConnected();
                    healthSequence = acceptedHealth.sequence();
                    renderAge = acceptedHealth.lastRenderAgeMillis();
                    ingressDepth = acceptedHealth.ingressQueueDepth();
                    renderDepth = acceptedHealth.renderQueueDepth();
                } else if (event.state() == SupervisorState.READY) {
                    rendererConnected = true;
                } else if (
                    event.state() != SupervisorState.DEGRADED &&
                    event.state() != SupervisorState.STOPPING
                ) {
                    rendererConnected = false;
                }
                Optional<RuntimeVersionId> lastKnownGood = "STABLE".equals(event.reasonCode())
                    ? stableRollback
                    : previous.lastKnownGoodVersion();
                return copyStatus(
                    previous,
                    event.state(),
                    supervisorStatus.runtimeVersion(),
                    lastKnownGood,
                    event.reasonCode(),
                    supervisorStatus.retryAt(),
                    rendererConnected,
                    healthSequence,
                    renderAge,
                    ingressDepth,
                    renderDepth,
                    supervisorStatus.rejectedSignals()
                );
            });
        }
    }

    private void publishStarting(InstalledRuntime runtime) {
        lifecycleState.updateAndGet(previous -> stopped.get()
            ? previous
            : SupervisorState.STARTING);
        Optional<RuntimeVersionId> rollbackTarget = rollbackTargetId();
        published.updateAndGet(previous -> stopped.get()
            ? previous
            : copyStatus(
                previous,
                SupervisorState.STARTING,
                Optional.of(runtime.id()),
                rollbackTarget,
                "STARTING",
                Optional.empty(),
                false,
                -1,
                0,
                0,
                0,
                previous.rejectedSignals()
            ));
    }

    private void publishInstallState(SupervisorState state, String reason) {
        String safe = safeReason(reason);
        published.updateAndGet(previous -> stopped.get()
            ? previous
            : copyStatus(
                previous,
                state,
                previous.activeVersion(),
                previous.lastKnownGoodVersion(),
                safe,
                Optional.empty(),
                previous.rendererConnected(),
                previous.lastHealthSequence(),
                previous.lastRenderAgeMillis(),
                previous.ingressQueueDepth(),
                previous.renderQueueDepth(),
                previous.rejectedSignals()
            ));
    }

    private void publishFailure(String reason) {
        lifecycleState.updateAndGet(previous -> stopped.get()
            ? previous
            : SupervisorState.FAILED);
        String safe = safeReason(reason);
        published.updateAndGet(previous -> stopped.get()
            ? previous
            : copyStatus(
                previous,
                SupervisorState.FAILED,
                previous.activeVersion(),
                previous.lastKnownGoodVersion(),
                safe,
                Optional.empty(),
                false,
                previous.lastHealthSequence(),
                previous.lastRenderAgeMillis(),
                previous.ingressQueueDepth(),
                previous.renderQueueDepth(),
                previous.rejectedSignals()
            ));
    }

    private RuntimeStatusSnapshot copyStatus(
        RuntimeStatusSnapshot previous,
        SupervisorState state,
        Optional<RuntimeVersionId> activeVersion,
        Optional<RuntimeVersionId> lastKnownGoodVersion,
        String reasonCode,
        Optional<Instant> retryAt,
        boolean rendererConnected,
        long healthSequence,
        long lastRenderAgeMillis,
        int ingressQueueDepth,
        int renderQueueDepth,
        long rejectedSignals
    ) {
        return new RuntimeStatusSnapshot(
            state,
            activeVersion,
            lastKnownGoodVersion,
            previous.runtimeApi(),
            clock.instant(),
            previous.publicHost(),
            previous.publicPort(),
            reasonCode,
            retryAt,
            rendererConnected,
            healthSequence,
            lastRenderAgeMillis,
            ingressQueueDepth,
            renderQueueDepth,
            rejectedSignals
        );
    }

    private boolean isCurrent(long generation, CancellationToken token) {
        return !stopped.get() &&
            generation == operationGeneration.get() &&
            activeInstall.get() == token &&
            !token.isCancelled();
    }

    private boolean runtimeAlreadyActive(InstalledRuntime runtime) {
        SupervisorState state = lifecycleState.get();
        boolean lifecycleOwnsProcess = state == SupervisorState.STARTING ||
            state == SupervisorState.READY ||
            state == SupervisorState.DEGRADED;
        return lifecycleOwnsProcess && published.get().activeVersion()
            .filter(runtime.id()::equals)
            .isPresent();
    }

    private void publishUpToDate(InstalledRuntime runtime) {
        Optional<RuntimeVersionId> rollbackTarget = rollbackTargetId();
        published.updateAndGet(previous -> stopped.get()
            ? previous
            : copyStatus(
                previous,
                lifecycleState.get(),
                Optional.of(runtime.id()),
                rollbackTarget,
                "UP_TO_DATE",
                Optional.empty(),
                previous.rendererConnected(),
                previous.lastHealthSequence(),
                previous.lastRenderAgeMillis(),
                previous.ingressQueueDepth(),
                previous.renderQueueDepth(),
                previous.rejectedSignals()
            ));
    }

    private Optional<RuntimeVersionId> rollbackTargetId() {
        try {
            return store.rollbackTarget(descriptor.runtimeApi()).map(InstalledRuntime::id);
        } catch (RuntimeStoreException error) {
            return published.get().lastKnownGoodVersion();
        }
    }

    private void submitCoordinator(Runnable action) {
        if (!enabled || stopped.get()) {
            return;
        }
        try {
            coordinator.execute(() -> {
                if (!stopped.get()) {
                    action.run();
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Stop closed the manager between the state check and submission.
        }
    }

    private boolean awaitSupervisorStop(long deadlineNanos) {
        long remaining = remainingNanos(deadlineNanos);
        if (remaining == 0) {
            return false;
        }
        try {
            supervisorScheduler.submit(() -> { }).get(remaining, TimeUnit.NANOSECONDS);
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | RejectedExecutionException | TimeoutException error) {
            return false;
        }
    }

    private void clearExpectedControl() {
        long generation = expectedControlGeneration.getAndSet(0);
        if (generation > 0) {
            controlChannel.clear(generation);
        }
    }

    private static String rendererSecret(String configured, Path secretFile) throws IOException {
        String value = configured == null ? "" : configured.strip();
        if (!value.isEmpty()) {
            return validateSecret(value);
        }
        if (Files.exists(secretFile, LinkOption.NOFOLLOW_LINKS)) {
            return readSecret(secretFile);
        }
        String generated = newNonce();
        try {
            writeSecret(secretFile, generated);
            return generated;
        } catch (FileAlreadyExistsException error) {
            return readSecret(secretFile);
        }
    }

    private static void writeSecret(Path path, String value) throws IOException {
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("runtime state directory unavailable");
        }
        Set<PosixFilePermission> permissions = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        );
        try {
            Files.createFile(path, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                permissions
            ));
        } catch (UnsupportedOperationException error) {
            Files.createFile(path);
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(value);
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        } catch (IOException | RuntimeException error) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanupFailure) {
                error.addSuppressed(cleanupFailure);
            }
            throw error;
        }
    }

    private static String readSecret(Path path) throws IOException {
        if (
            Files.isSymbolicLink(path) ||
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
            Files.size(path) > MAXIMUM_SECRET_CHARACTERS
        ) {
            throw new IOException("invalid renderer credential file");
        }
        return validateSecret(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static String validateSecret(String value) {
        if (
            value.length() < MINIMUM_SECRET_CHARACTERS ||
            value.length() > MAXIMUM_SECRET_CHARACTERS ||
            value.codePoints().anyMatch(character -> character < 0x21 || character > 0x7e)
        ) {
            throw new IllegalArgumentException("invalid renderer credential");
        }
        return value;
    }

    private static String newNonce() {
        byte[] bytes = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String rendererUrl(String host, int port) {
        String renderedHost = host.contains(":") ? "[" + host + "]" : host;
        return "ws://" + renderedHost + ":" + port;
    }

    private static ReleaseDescriptor descriptorForChannel(
        RuntimeConfig config,
        ReleaseDescriptor descriptor
    ) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(descriptor, "descriptor");
        if (config.channel() == RuntimeConfig.ReleaseChannel.STABLE) {
            return descriptor;
        }
        URI stableUri = descriptor.manifestUri();
        String stableSegment = "/stable/";
        String path = stableUri.getPath();
        int first = path == null ? -1 : path.indexOf(stableSegment);
        if (first < 0 || path.indexOf(stableSegment, first + stableSegment.length()) >= 0) {
            throw new IllegalArgumentException("release descriptor has no unique stable channel");
        }
        String betaPath = path.substring(0, first) + "/beta/" +
            path.substring(first + stableSegment.length());
        try {
            URI betaUri = new URI(
                stableUri.getScheme(),
                stableUri.getUserInfo(),
                stableUri.getHost(),
                stableUri.getPort(),
                betaPath,
                null,
                null
            );
            return new ReleaseDescriptor(
                betaUri,
                descriptor.trustedKeys(),
                descriptor.allowedHosts(),
                descriptor.runtimeApi(),
                descriptor.minimumGeneration()
            );
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("invalid beta release descriptor", error);
        }
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicLong sequence = new AtomicLong();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static boolean awaitTermination(ExecutorService executor, long deadlineNanos) {
        long remaining = remainingNanos(deadlineNanos);
        if (remaining == 0) {
            return executor.isTerminated();
        }
        try {
            return executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long remainingNanos(long deadlineNanos) {
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }

    private static void shutdownCreated(ExecutorService... executors) {
        for (ExecutorService executor : executors) {
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }

    private static String safeReason(String value) {
        Objects.requireNonNull(value, "reasonCode");
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 64) {
            throw new IllegalArgumentException("invalid runtime reason");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if ((character < 'A' || character > 'Z') && character != '_') {
                throw new IllegalArgumentException("invalid runtime reason");
            }
        }
        return normalized;
    }

    private static boolean isHealthEvent(String reasonCode) {
        return "HEALTHY".equals(reasonCode) ||
            "HEALTH_RECOVERED".equals(reasonCode) ||
            "HEALTH_FAILED".equals(reasonCode);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public enum InitializationFailure {
        INVALID_RENDERER,
        CHANNEL_UNAVAILABLE,
        STORE_FAILURE,
        UNSUPPORTED_PLATFORM,
        INITIALIZATION_FAILED
    }

    public static final class InitializationException extends Exception {
        private final InitializationFailure reason;

        InitializationException(InitializationFailure reason) {
            super(reason.name());
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        InitializationException(InitializationFailure reason, Throwable cause) {
            super(reason.name(), cause);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public InitializationFailure reason() {
            return reason;
        }
    }

    private static final class CallbackBridge {
        private PaperRuntimeManager manager;
        private RuntimePaths paths;
        private String rendererHost;
        private int rendererPort;

        synchronized void bind(
            PaperRuntimeManager value,
            RuntimePaths runtimePaths,
            String host,
            int port
        ) {
            if (manager != null) {
                throw new IllegalStateException("runtime callback bridge already bound");
            }
            manager = Objects.requireNonNull(value, "manager");
            paths = Objects.requireNonNull(runtimePaths, "runtimePaths");
            rendererHost = Objects.requireNonNull(host, "rendererHost");
            rendererPort = port;
        }

        void ready(RuntimeReady ready) {
            manager().onRuntimeReady(ready);
        }

        void health(RuntimeHealth health) {
            manager().onRuntimeHealth(health);
        }

        void disconnected(long generation) {
            manager().onRendererDisconnect(generation);
        }

        void installEvent(RuntimeInstallEvent event) {
            manager().onInstallEvent(event);
        }

        void supervisorEvent(SupervisorEvent event) {
            manager().onSupervisorEvent(event);
        }

        RuntimeLaunch launch(InstalledRuntime runtime, long generation, String nonce) {
            LaunchContext context = launchContext();
            PaperRuntimeManager owner = context.manager();
            return owner.createLaunch(
                runtime,
                generation,
                nonce,
                context.paths(),
                context.rendererHost(),
                context.rendererPort()
            );
        }

        private synchronized LaunchContext launchContext() {
            return new LaunchContext(
                manager(),
                Objects.requireNonNull(paths, "runtimePaths"),
                Objects.requireNonNull(rendererHost, "rendererHost"),
                rendererPort
            );
        }

        private synchronized PaperRuntimeManager manager() {
            if (manager == null) {
                throw new IllegalStateException("runtime callback bridge is not bound");
            }
            return manager;
        }

        private record LaunchContext(
            PaperRuntimeManager manager,
            RuntimePaths paths,
            String rendererHost,
            int rendererPort
        ) { }
    }
}
