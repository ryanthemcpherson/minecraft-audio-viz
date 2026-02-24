package com.audioviz.bitmap;

/**
 * Tracks per-zone bitmap render timing for diagnostics.
 *
 * <p>Records render durations and skipped frames. Provides snapshot-and-reset
 * for periodic logging without allocating on the hot path.
 */
public class BitmapRenderTimer {

    private long totalRenderNanos;
    private long maxRenderNanos;
    private int frameCount;
    private int skipCount;

    public void recordRender(long nanos) {
        totalRenderNanos += nanos;
        if (nanos > maxRenderNanos) {
            maxRenderNanos = nanos;
        }
        frameCount++;
    }

    public void recordSkip() {
        skipCount++;
    }

    public int getFrameCount() { return frameCount; }
    public int getSkipCount() { return skipCount; }
    public long getMaxRenderNanos() { return maxRenderNanos; }

    public long getAvgRenderNanos() {
        return frameCount > 0 ? totalRenderNanos / frameCount : 0;
    }

    /**
     * Snapshot current stats and reset counters.
     */
    public Stats snapshotAndReset() {
        Stats stats = new Stats(frameCount, skipCount, totalRenderNanos,
                                maxRenderNanos, getAvgRenderNanos());
        totalRenderNanos = 0;
        maxRenderNanos = 0;
        frameCount = 0;
        skipCount = 0;
        return stats;
    }

    public record Stats(int frameCount, int skipCount, long totalNanos,
                         long maxNanos, long avgNanos) {
        public double maxMs() { return maxNanos / 1_000_000.0; }
        public double avgMs() { return avgNanos / 1_000_000.0; }
    }
}
