package com.audioviz.metrics;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class MetricsDisplayTest {

    @Nested
    @DisplayName("Metric Formatting")
    class Formatting {

        @Test
        @DisplayName("formatRenderTime rounds to 1 decimal")
        void formatRenderTime() {
            assertEquals("4.2ms", MetricsDisplay.formatRenderTime(4.23456));
        }

        @Test
        @DisplayName("formatRenderTime handles zero")
        void formatRenderTimeZero() {
            assertEquals("0.0ms", MetricsDisplay.formatRenderTime(0.0));
        }

        @Test
        @DisplayName("formatEntityCount shows used/total")
        void formatEntityCount() {
            assertEquals("312/500", MetricsDisplay.formatEntityCount(312, 500));
        }

        @Test
        @DisplayName("formatBpm shows integer when confident")
        void formatBpmConfident() {
            assertEquals("128 BPM", MetricsDisplay.formatBpm(128.4, 0.8));
        }

        @Test
        @DisplayName("formatBpm shows dash when not confident")
        void formatBpmNotConfident() {
            assertEquals("-- BPM", MetricsDisplay.formatBpm(128.4, 0.3));
        }

        @Test
        @DisplayName("formatDjStatus connected")
        void formatDjConnected() {
            assertEquals("Connected (128 BPM)",
                MetricsDisplay.formatDjStatus(true, false, 128.0, 0.9));
        }

        @Test
        @DisplayName("formatDjStatus disconnected")
        void formatDjDisconnected() {
            assertEquals("Disconnected",
                MetricsDisplay.formatDjStatus(false, false, 0, 0));
        }

        @Test
        @DisplayName("formatDjStatus stale")
        void formatDjStale() {
            assertEquals("Signal Lost",
                MetricsDisplay.formatDjStatus(true, true, 128.0, 0.9));
        }
    }
}
