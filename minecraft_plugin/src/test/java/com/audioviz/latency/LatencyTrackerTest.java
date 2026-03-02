package com.audioviz.latency;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class LatencyTrackerTest {

    @Nested
    @DisplayName("Rolling Window Stats")
    class RollingStats {

        @Test
        @DisplayName("average of single sample")
        void singleSample() {
            var tracker = new LatencyTracker.RollingWindow(10);
            tracker.record(50.0);
            assertEquals(50.0, tracker.getAvg(), 0.001);
        }

        @Test
        @DisplayName("average of multiple samples")
        void multipleSamples() {
            var tracker = new LatencyTracker.RollingWindow(10);
            tracker.record(10.0);
            tracker.record(20.0);
            tracker.record(30.0);
            assertEquals(20.0, tracker.getAvg(), 0.001);
        }

        @Test
        @DisplayName("window evicts oldest when full")
        void eviction() {
            var tracker = new LatencyTracker.RollingWindow(3);
            tracker.record(10.0);
            tracker.record(20.0);
            tracker.record(30.0);
            tracker.record(40.0); // evicts 10
            assertEquals(30.0, tracker.getAvg(), 0.001);
        }

        @Test
        @DisplayName("p95 calculation")
        void p95() {
            var tracker = new LatencyTracker.RollingWindow(100);
            for (int i = 1; i <= 100; i++) {
                tracker.record(i);
            }
            assertEquals(95.0, tracker.getP95(), 1.0);
        }

        @Test
        @DisplayName("max tracks highest value")
        void max() {
            var tracker = new LatencyTracker.RollingWindow(10);
            tracker.record(5.0);
            tracker.record(50.0);
            tracker.record(15.0);
            assertEquals(50.0, tracker.getMax(), 0.001);
        }

        @Test
        @DisplayName("empty window returns zero")
        void emptyReturnsZero() {
            var tracker = new LatencyTracker.RollingWindow(10);
            assertEquals(0.0, tracker.getAvg());
            assertEquals(0.0, tracker.getP95());
            assertEquals(0.0, tracker.getMax());
            assertEquals(0.0, tracker.getJitter());
        }

        @Test
        @DisplayName("jitter is stddev of samples")
        void jitter() {
            var tracker = new LatencyTracker.RollingWindow(4);
            tracker.record(10.0);
            tracker.record(10.0);
            tracker.record(10.0);
            tracker.record(10.0);
            assertEquals(0.0, tracker.getJitter(), 0.001);
        }
    }

    @Nested
    @DisplayName("Clock Offset")
    class ClockOffset {

        @Test
        @DisplayName("computes offset on first sample")
        void firstSample() {
            var tracker = new LatencyTracker();
            long localMs = 1000;
            double remoteTs = 0.5; // 500ms in seconds
            tracker.recordNetworkLatency(remoteTs, localMs);
            // offset = 1000 - 500 = 500, latency = 0
            // Second sample uses offset
            tracker.recordNetworkLatency(0.6, 1120);
            // expected: 1120 - 600 - 500 = 20ms
            assertEquals(20.0, tracker.getNetworkStats().getAvg(), 1.0);
        }

        @Test
        @DisplayName("negative latency clamped to zero")
        void negativeClamped() {
            var tracker = new LatencyTracker();
            tracker.recordNetworkLatency(10.0, 1000); // offset = 1000 - 10000 = -9000
            tracker.recordNetworkLatency(10.001, 1000); // 1000 - 10001 - (-9000) = -1 -> clamp to 0
            assertEquals(0.0, tracker.getNetworkStats().getAvg(), 0.001);
        }
    }

    @Nested
    @DisplayName("Segment Tracking")
    class Segments {

        @Test
        @DisplayName("total latency sums segments")
        void totalLatency() {
            var tracker = new LatencyTracker();
            tracker.recordNetworkLatency(1.0, 1010);
            tracker.recordProcessingLatency(5.0);
            double total = tracker.getTotalAvgMs();
            assertTrue(total >= 5.0);
        }
    }
}
