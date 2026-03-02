package com.audioviz.connection;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionStateListenerTest {

    @Nested
    @DisplayName("Staleness Detection")
    class StalenessDetection {

        @Test
        @DisplayName("not stale when frame received recently")
        void notStaleWhenRecent() {
            long now = System.currentTimeMillis();
            assertFalse(ConnectionStateListener.isStale(now - 1000, now, 3000));
        }

        @Test
        @DisplayName("stale when no frame for longer than threshold")
        void staleAfterThreshold() {
            long now = System.currentTimeMillis();
            assertTrue(ConnectionStateListener.isStale(now - 4000, now, 3000));
        }

        @Test
        @DisplayName("not stale at exact threshold boundary")
        void notStaleAtExactBoundary() {
            long now = System.currentTimeMillis();
            assertFalse(ConnectionStateListener.isStale(now - 3000, now, 3000));
        }

        @Test
        @DisplayName("stale when lastFrameMs is 0 (never received)")
        void staleWhenNeverReceived() {
            long now = System.currentTimeMillis();
            assertTrue(ConnectionStateListener.isStale(0, now, 3000));
        }
    }

    @Nested
    @DisplayName("Brightness Ramp")
    class BrightnessRamp {

        @Test
        @DisplayName("ramp computes intermediate values")
        void rampIntermediate() {
            double result = ConnectionStateListener.computeRampedBrightness(
                1.0, 0.3, 10, 5);
            assertEquals(0.65, result, 0.001);
        }

        @Test
        @DisplayName("ramp at start returns current")
        void rampAtStart() {
            double result = ConnectionStateListener.computeRampedBrightness(
                1.0, 0.3, 20, 0);
            assertEquals(1.0, result, 0.001);
        }

        @Test
        @DisplayName("ramp at end returns target")
        void rampAtEnd() {
            double result = ConnectionStateListener.computeRampedBrightness(
                1.0, 0.3, 20, 20);
            assertEquals(0.3, result, 0.001);
        }

        @Test
        @DisplayName("ramp past end clamps to target")
        void rampPastEnd() {
            double result = ConnectionStateListener.computeRampedBrightness(
                1.0, 0.3, 20, 25);
            assertEquals(0.3, result, 0.001);
        }
    }
}
