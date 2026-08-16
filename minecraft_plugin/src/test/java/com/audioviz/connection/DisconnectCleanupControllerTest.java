package com.audioviz.connection;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisconnectCleanupControllerTest {

    @Test
    void disconnectSchedulesOneCleanupWithConfiguredDelay() {
        FakeScheduler scheduler = new FakeScheduler();
        DisconnectCleanupController controller =
            new DisconnectCleanupController(scheduler, () -> { }, 100L);

        controller.disconnected();

        assertEquals(1, scheduler.tasks.size());
        assertEquals(100L, scheduler.tasks.getFirst().delayTicks);
        assertFalse(scheduler.tasks.getFirst().cancelled);
    }

    @Test
    void reconnectCancelsPendingCleanup() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger cleanups = new AtomicInteger();
        DisconnectCleanupController controller =
            new DisconnectCleanupController(scheduler, cleanups::incrementAndGet, 100L);

        controller.disconnected();
        controller.connected();
        scheduler.runAll();

        assertTrue(scheduler.tasks.getFirst().cancelled);
        assertEquals(0, cleanups.get());
    }

    @Test
    void repeatedDisconnectReplacesPendingCleanup() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger cleanups = new AtomicInteger();
        DisconnectCleanupController controller =
            new DisconnectCleanupController(scheduler, cleanups::incrementAndGet, 100L);

        controller.disconnected();
        FakeTask first = scheduler.tasks.getFirst();
        controller.disconnected();
        FakeTask second = scheduler.tasks.getLast();

        assertTrue(first.cancelled);
        assertFalse(second.cancelled);
        first.forceRun();
        assertEquals(0, cleanups.get());
        second.runIfActive();
        assertEquals(1, cleanups.get());
    }

    @Test
    void expiredTaskRunsCleanupOnlyOnce() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger cleanups = new AtomicInteger();
        DisconnectCleanupController controller =
            new DisconnectCleanupController(scheduler, cleanups::incrementAndGet, 0L);

        controller.disconnected();
        FakeTask task = scheduler.tasks.getFirst();
        task.forceRun();
        task.forceRun();

        assertEquals(1, cleanups.get());
    }

    @Test
    void stopCancelsPendingCleanupAndBlocksStaleExpiry() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger cleanups = new AtomicInteger();
        DisconnectCleanupController controller =
            new DisconnectCleanupController(scheduler, cleanups::incrementAndGet, 100L);

        controller.disconnected();
        FakeTask task = scheduler.tasks.getFirst();
        controller.stop();
        task.forceRun();
        controller.disconnected();

        assertTrue(task.cancelled);
        assertEquals(0, cleanups.get());
        assertEquals(1, scheduler.tasks.size());
    }

    @Test
    void negativeDelayIsRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new DisconnectCleanupController(new FakeScheduler(), () -> { }, -1L)
        );
    }

    private static final class FakeScheduler implements DisconnectCleanupController.Scheduler {

        private final List<FakeTask> tasks = new ArrayList<>();

        @Override
        public DisconnectCleanupController.ScheduledTask schedule(
            Runnable action,
            long delayTicks
        ) {
            FakeTask task = new FakeTask(action, delayTicks);
            tasks.add(task);
            return task;
        }

        private void runAll() {
            tasks.forEach(FakeTask::runIfActive);
        }
    }

    private static final class FakeTask implements DisconnectCleanupController.ScheduledTask {

        private final Runnable action;
        private final long delayTicks;
        private boolean cancelled;

        private FakeTask(Runnable action, long delayTicks) {
            this.action = action;
            this.delayTicks = delayTicks;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        private void runIfActive() {
            if (!cancelled) {
                action.run();
            }
        }

        private void forceRun() {
            action.run();
        }
    }
}
