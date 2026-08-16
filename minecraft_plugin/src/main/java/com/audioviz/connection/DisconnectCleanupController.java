package com.audioviz.connection;

import java.util.Objects;

/** Schedules one reconnect-cancellable cleanup after the last client disconnects. */
public final class DisconnectCleanupController {

    private final Object lock = new Object();
    private final Scheduler scheduler;
    private final Runnable cleanup;
    private final long delayTicks;

    private long generation;
    private ScheduledTask pendingTask;
    private boolean stopped;

    public DisconnectCleanupController(
        Scheduler scheduler,
        Runnable cleanup,
        long delayTicks
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        if (delayTicks < 0) {
            throw new IllegalArgumentException("delayTicks must not be negative");
        }
        this.delayTicks = delayTicks;
    }

    public void connected() {
        ScheduledTask taskToCancel;
        synchronized (lock) {
            if (stopped) {
                return;
            }
            generation++;
            taskToCancel = pendingTask;
            pendingTask = null;
        }
        cancel(taskToCancel);
    }

    public void disconnected() {
        ScheduledTask taskToCancel;
        long scheduledGeneration;
        synchronized (lock) {
            if (stopped) {
                return;
            }
            scheduledGeneration = ++generation;
            taskToCancel = pendingTask;
            pendingTask = null;
        }
        cancel(taskToCancel);

        ScheduledTask scheduledTask = scheduler.schedule(
            () -> cleanupIfCurrent(scheduledGeneration),
            delayTicks
        );

        boolean keepTask;
        synchronized (lock) {
            keepTask = !stopped && generation == scheduledGeneration;
            if (keepTask) {
                pendingTask = scheduledTask;
            }
        }
        if (!keepTask) {
            scheduledTask.cancel();
        }
    }

    public void stop() {
        ScheduledTask taskToCancel;
        synchronized (lock) {
            if (stopped) {
                return;
            }
            stopped = true;
            generation++;
            taskToCancel = pendingTask;
            pendingTask = null;
        }
        cancel(taskToCancel);
    }

    private void cleanupIfCurrent(long scheduledGeneration) {
        synchronized (lock) {
            if (stopped || generation != scheduledGeneration) {
                return;
            }
            generation++;
            pendingTask = null;
        }
        cleanup.run();
    }

    private static void cancel(ScheduledTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    @FunctionalInterface
    public interface Scheduler {
        ScheduledTask schedule(Runnable action, long delayTicks);
    }

    @FunctionalInterface
    public interface ScheduledTask {
        void cancel();
    }
}
