package com.audioviz.sidecar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owns bootstrap and service process lifetime without blocking Paper's main thread. */
public final class VjSidecarManager {

    public enum State {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        FAILED
    }

    @FunctionalInterface
    public interface ProcessLauncher {
        Process start(List<String> command, Map<String, String> environment, Path directory)
            throws IOException;
    }

    @FunctionalInterface
    public interface WorkerLauncher {
        Thread launch(Runnable task);
    }

    private final VjSidecarLaunchPlan plan;
    private final ProcessLauncher processLauncher;
    private final WorkerLauncher workerLauncher;
    private final Consumer<String> log;
    private volatile State state = State.STOPPED;
    private volatile Process ownedProcess;

    public VjSidecarManager(VjSidecarLaunchPlan plan, Consumer<String> log) {
        this(plan, VjSidecarManager::launchProcess, VjSidecarManager::launchWorker, log);
    }

    VjSidecarManager(
        VjSidecarLaunchPlan plan,
        ProcessLauncher processLauncher,
        WorkerLauncher workerLauncher,
        Consumer<String> log
    ) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.processLauncher = Objects.requireNonNull(processLauncher, "processLauncher");
        this.workerLauncher = Objects.requireNonNull(workerLauncher, "workerLauncher");
        this.log = Objects.requireNonNull(log, "log");
    }

    public synchronized boolean start() {
        if (state != State.STOPPED) {
            return false;
        }
        state = State.STARTING;
        workerLauncher.launch(this::runLifecycle);
        return true;
    }

    public void stop() {
        Process process;
        synchronized (this) {
            if (state == State.STOPPED) {
                return;
            }
            state = State.STOPPING;
            process = ownedProcess;
        }
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        synchronized (this) {
            if (ownedProcess == null || !ownedProcess.isAlive()) {
                ownedProcess = null;
                state = State.STOPPED;
            }
        }
    }

    public State state() {
        return state;
    }

    private void runLifecycle() {
        try {
            int bootstrapExit = runToCompletion(plan.bootstrapCommand(), "bootstrap");
            if (bootstrapExit != 0) {
                fail("VJ bootstrap exited with status " + bootstrapExit);
                return;
            }
            synchronized (this) {
                if (state == State.STOPPING || state == State.STOPPED) {
                    state = State.STOPPED;
                    return;
                }
            }
            plan.validateIdentity();
            Process service = processLauncher.start(
                plan.serviceCommand(),
                plan.childEnvironment(),
                plan.projectRoot()
            );
            synchronized (this) {
                ownedProcess = service;
                if (state == State.STOPPING) {
                    service.destroy();
                } else {
                    state = State.RUNNING;
                    log.accept("VJ sidecar started");
                }
            }
            drainOutput(service, "service");
            int serviceExit = service.waitFor();
            synchronized (this) {
                ownedProcess = null;
                if (state == State.STOPPING || state == State.STOPPED) {
                    state = State.STOPPED;
                } else {
                    state = State.FAILED;
                    log.accept("VJ sidecar exited with status " + serviceExit);
                }
            }
        } catch (IOException | RuntimeException failure) {
            fail("VJ sidecar failed: " + failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail("VJ sidecar worker was interrupted");
        }
    }

    private int runToCompletion(List<String> command, String phase)
        throws IOException, InterruptedException {
        Process process = processLauncher.start(command, plan.childEnvironment(), plan.projectRoot());
        synchronized (this) {
            ownedProcess = process;
            if (state == State.STOPPING) {
                process.destroy();
            }
        }
        drainOutput(process, phase);
        int exitCode = process.waitFor();
        synchronized (this) {
            if (ownedProcess == process) {
                ownedProcess = null;
            }
        }
        return exitCode;
    }

    private void drainOutput(Process process, String phase) throws IOException {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.accept("VJ " + phase + ": " + line);
            }
        }
    }

    private synchronized void fail(String message) {
        ownedProcess = null;
        if (state == State.STOPPING) {
            state = State.STOPPED;
        } else {
            state = State.FAILED;
            log.accept(message);
        }
    }

    private static Process launchProcess(
        List<String> command,
        Map<String, String> environment,
        Path directory
    ) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true);
        builder.environment().putAll(environment);
        return builder.start();
    }

    private static Thread launchWorker(Runnable task) {
        Thread worker = new Thread(task, "AudioViz-VJ-Sidecar");
        worker.setDaemon(true);
        worker.start();
        return worker;
    }
}
