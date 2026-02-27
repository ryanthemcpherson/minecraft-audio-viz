package com.audioviz.bitmap;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks per-zone bitmap render timing for diagnostics.
 *
 * <p>Records render durations and skipped frames. Provides snapshot-and-reset
 * for periodic logging without allocating on the hot path.
 *
 * <p>Thread safety: {@code recordRender} is called from the render thread,
 * {@code recordSkip} from the main thread. Atomics ensure correct concurrent updates.
 */
public class BitmapRenderTimer {

    private final AtomicLong totalRenderNanos = new AtomicLong();
    private final AtomicLong maxRenderNanos = new AtomicLong();
    private final AtomicInteger frameCount = new AtomicInteger();
    private final AtomicInteger skipCount = new AtomicInteger();

    public void recordRender(long nanos) {
        totalRenderNanos.addAndGet(nanos);
        maxRenderNanos.updateAndGet(prev -> Math.max(prev, nanos));
        frameCount.incrementAndGet();
    }

    public void recordSkip() {
        skipCount.incrementAndGet();
    }

    public int getFrameCount() { return frameCount.get(); }
    public int getSkipCount() { return skipCount.get(); }
    public long getMaxRenderNanos() { return maxRenderNanos.get(); }

    public long getAvgRenderNanos() {
        int count = frameCount.get();
        return count > 0 ? totalRenderNanos.get() / count : 0;
    }

    /**
     * Snapshot current stats and reset counters.
     */
    public synchronized Stats snapshotAndReset() {
        int frames = frameCount.get();
        int skips = skipCount.get();
        long total = totalRenderNanos.get();
        long max = maxRenderNanos.get();
        long avg = frames > 0 ? total / frames : 0;

        totalRenderNanos.set(0);
        maxRenderNanos.set(0);
        frameCount.set(0);
        skipCount.set(0);

        return new Stats(frames, skips, total, max, avg);
    }

    public record Stats(int frameCount, int skipCount, long totalNanos,
                         long maxNanos, long avgNanos) {
        public double maxMs() { return maxNanos / 1_000_000.0; }
        public double avgMs() { return avgNanos / 1_000_000.0; }
    }
}
