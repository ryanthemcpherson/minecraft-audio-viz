package com.audioviz.latency;

import java.util.Arrays;

/**
 * Tracks end-to-end latency across the audio pipeline.
 * Three segments: network (DJ->Plugin), processing (queue->tick), render.
 */
public class LatencyTracker {

    private final RollingWindow networkLatency = new RollingWindow(100);
    private final RollingWindow processingLatency = new RollingWindow(100);

    private boolean clockOffsetInitialized = false;
    private long clockOffsetMs = 0;

    /**
     * Record network latency from an incoming audio frame.
     * @param remoteTimestampSec timestamp from DJ client (Unix seconds, double)
     * @param localReceiveMs local receive time (System.currentTimeMillis())
     */
    public void recordNetworkLatency(double remoteTimestampSec, long localReceiveMs) {
        long remoteMs = (long) (remoteTimestampSec * 1000);

        if (!clockOffsetInitialized) {
            clockOffsetMs = localReceiveMs - remoteMs;
            clockOffsetInitialized = true;
            return; // First sample calibrates, don't record
        }

        long latencyMs = localReceiveMs - remoteMs - clockOffsetMs;
        networkLatency.record(Math.max(0, latencyMs));
    }

    /**
     * Record processing latency (time from message receive to tick consumption).
     */
    public void recordProcessingLatency(double ms) {
        processingLatency.record(Math.max(0, ms));
    }

    public RollingWindow getNetworkStats() { return networkLatency; }
    public RollingWindow getProcessingStats() { return processingLatency; }

    /**
     * Get total average latency (network + processing).
     * Render latency is tracked separately by BitmapRenderTimer.
     */
    public double getTotalAvgMs() {
        return networkLatency.getAvg() + processingLatency.getAvg();
    }

    /**
     * Rolling window for latency samples with avg, p95, max, jitter.
     */
    public static class RollingWindow {

        private final double[] samples;
        private final int capacity;
        private int count = 0;
        private int writeIndex = 0;

        public RollingWindow(int capacity) {
            this.capacity = capacity;
            this.samples = new double[capacity];
        }

        public void record(double value) {
            samples[writeIndex] = value;
            writeIndex = (writeIndex + 1) % capacity;
            if (count < capacity) count++;
        }

        public double getAvg() {
            if (count == 0) return 0.0;
            double sum = 0;
            for (int i = 0; i < count; i++) sum += samples[i];
            return sum / count;
        }

        public double getP95() {
            if (count == 0) return 0.0;
            double[] sorted = new double[count];
            System.arraycopy(samples, 0, sorted, 0, count);
            Arrays.sort(sorted);
            int index = (int) Math.ceil(count * 0.95) - 1;
            return sorted[Math.max(0, index)];
        }

        public double getMax() {
            if (count == 0) return 0.0;
            double max = samples[0];
            for (int i = 1; i < count; i++) {
                if (samples[i] > max) max = samples[i];
            }
            return max;
        }

        public double getJitter() {
            if (count < 2) return 0.0;
            double avg = getAvg();
            double sumSqDiff = 0;
            for (int i = 0; i < count; i++) {
                double diff = samples[i] - avg;
                sumSqDiff += diff * diff;
            }
            return Math.sqrt(sumSqDiff / count);
        }

        public int getCount() { return count; }
    }
}
