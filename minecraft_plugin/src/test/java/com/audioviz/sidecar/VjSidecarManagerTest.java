package com.audioviz.sidecar;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VjSidecarManagerTest {

    @Test
    void startsAsynchronouslyInOrderAndStopsOnlyOwnedService() throws Exception {
        FakeProcess bootstrap = FakeProcess.completed(0, "bootstrap complete\n");
        FakeProcess service = FakeProcess.running("service ready\n");
        Queue<FakeProcess> processes = new ArrayDeque<>(List.of(bootstrap, service));
        CountDownLatch workerStarted = new CountDownLatch(1);
        VjSidecarLaunchPlan plan = org.mockito.Mockito.mock(VjSidecarLaunchPlan.class);
        org.mockito.Mockito.when(plan.bootstrapCommand()).thenReturn(List.of("bootstrap"));
        org.mockito.Mockito.when(plan.serviceCommand()).thenReturn(List.of("service"));
        org.mockito.Mockito.when(plan.childEnvironment()).thenReturn(Map.of());
        org.mockito.Mockito.when(plan.projectRoot()).thenReturn(Path.of("."));
        org.mockito.Mockito.when(plan.validateIdentity()).thenReturn(true);

        VjSidecarManager manager = new VjSidecarManager(
            plan,
            (command, environment, directory) -> processes.remove(),
            task -> {
                Thread worker = new Thread(() -> {
                    workerStarted.countDown();
                    task.run();
                });
                worker.setDaemon(true);
                worker.start();
                return worker;
            },
            message -> { }
        );

        long startedAt = System.nanoTime();
        assertTrue(manager.start());
        assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).toMillis() < 100);
        assertTrue(workerStarted.await(1, TimeUnit.SECONDS));
        assertTrue(waitForState(manager, VjSidecarManager.State.RUNNING));
        assertFalse(manager.start());

        manager.stop();

        assertTrue(service.destroyed);
        assertFalse(bootstrap.destroyed);
        assertTrue(waitForState(manager, VjSidecarManager.State.STOPPED));
    }

    @Test
    void bootstrapFailureNeverStartsService() throws Exception {
        FakeProcess bootstrap = FakeProcess.completed(1, "bootstrap failed\n");
        VjSidecarLaunchPlan plan = org.mockito.Mockito.mock(VjSidecarLaunchPlan.class);
        org.mockito.Mockito.when(plan.bootstrapCommand()).thenReturn(List.of("bootstrap"));
        org.mockito.Mockito.when(plan.childEnvironment()).thenReturn(Map.of());
        org.mockito.Mockito.when(plan.projectRoot()).thenReturn(Path.of("."));

        VjSidecarManager manager = new VjSidecarManager(
            plan,
            (command, environment, directory) -> bootstrap,
            task -> {
                Thread worker = new Thread(task);
                worker.setDaemon(true);
                worker.start();
                return worker;
            },
            message -> { }
        );

        assertTrue(manager.start());
        assertTrue(waitForState(manager, VjSidecarManager.State.FAILED));
        assertFalse(bootstrap.destroyed);
    }

    private static boolean waitForState(
        VjSidecarManager manager,
        VjSidecarManager.State expected
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (manager.state() == expected) {
                return true;
            }
            Thread.sleep(10);
        }
        return manager.state() == expected;
    }

    private static final class FakeProcess extends Process {
        private final InputStream output;
        private final CountDownLatch completion;
        private final int exitCode;
        private volatile boolean destroyed;

        private FakeProcess(int exitCode, String output, boolean running) {
            this.exitCode = exitCode;
            this.output = new ByteArrayInputStream(output.getBytes());
            this.completion = new CountDownLatch(running ? 1 : 0);
        }

        static FakeProcess completed(int exitCode, String output) {
            return new FakeProcess(exitCode, output, false);
        }

        static FakeProcess running(String output) {
            return new FakeProcess(0, output, true);
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return output;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            completion.await();
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return completion.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (completion.getCount() != 0) {
                throw new IllegalThreadStateException("running");
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyed = true;
            completion.countDown();
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return completion.getCount() != 0;
        }
    }
}
