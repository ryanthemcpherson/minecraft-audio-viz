package com.audioviz.entities;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks statistics for entity updates.
 */
public class EntityUpdateStats {

    private final AtomicLong totalUpdatesProcessed = new AtomicLong(0);
    private final AtomicLong updatesThisSecond = new AtomicLong(0);
    private volatile long lastSecondTimestamp = System.currentTimeMillis();
    private volatile long updatesPerSecond = 0;

    /**
     * Record that an update was processed.
     */
    public void recordUpdate() {
        totalUpdatesProcessed.incrementAndGet();
        updatesThisSecond.incrementAndGet();

        // Calculate updates per second
        long now = System.currentTimeMillis();
        if (now - lastSecondTimestamp >= 1000) {
            updatesPerSecond = updatesThisSecond.getAndSet(0);
            lastSecondTimestamp = now;
        }
    }

    /**
     * Record multiple updates.
     */
    public void recordUpdates(int count) {
        totalUpdatesProcessed.addAndGet(count);
        updatesThisSecond.addAndGet(count);

        long now = System.currentTimeMillis();
        if (now - lastSecondTimestamp >= 1000) {
            updatesPerSecond = updatesThisSecond.getAndSet(0);
            lastSecondTimestamp = now;
        }
    }

    /**
     * Get total updates processed since startup.
     */
    public long getTotalUpdatesProcessed() {
        return totalUpdatesProcessed.get();
    }

    /**
     * Get current updates per second.
     */
    public long getUpdatesPerSecond() {
        return updatesPerSecond;
    }

    /**
     * Reset all statistics.
     */
    public void reset() {
        totalUpdatesProcessed.set(0);
        updatesThisSecond.set(0);
        updatesPerSecond = 0;
    }
}
