package com.audioviz.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for InputSanitizer utility class.
 * Verifies that NaN, Infinity, and out-of-range values are handled correctly.
 */
class InputSanitizerTest {

    @Nested
    @DisplayName("sanitizeDouble")
    class SanitizeDoubleTests {
        @Test
        void normalValuePassesThrough() {
            assertEquals(0.5, InputSanitizer.sanitizeDouble(0.5, 0.0, 1.0, 0.0));
        }

        @Test
        void valueAtBoundsPassesThrough() {
            assertEquals(0.0, InputSanitizer.sanitizeDouble(0.0, 0.0, 1.0, 0.5));
            assertEquals(1.0, InputSanitizer.sanitizeDouble(1.0, 0.0, 1.0, 0.5));
        }

        @Test
        void valueBelowMinClamped() {
            assertEquals(0.0, InputSanitizer.sanitizeDouble(-0.5, 0.0, 1.0, 0.5));
        }

        @Test
        void valueAboveMaxClamped() {
            assertEquals(1.0, InputSanitizer.sanitizeDouble(2.5, 0.0, 1.0, 0.5));
        }

        @Test
        void nanReturnsDefault() {
            assertEquals(0.5, InputSanitizer.sanitizeDouble(Double.NaN, 0.0, 1.0, 0.5));
        }

        @Test
        void positiveInfinityReturnsDefault() {
            assertEquals(0.5, InputSanitizer.sanitizeDouble(Double.POSITIVE_INFINITY, 0.0, 1.0, 0.5));
        }

        @Test
        void negativeInfinityReturnsDefault() {
            assertEquals(0.5, InputSanitizer.sanitizeDouble(Double.NEGATIVE_INFINITY, 0.0, 1.0, 0.5));
        }
    }

    @Nested
    @DisplayName("sanitizeFloat")
    class SanitizeFloatTests {
        @Test
        void normalValuePassesThrough() {
            assertEquals(0.5f, InputSanitizer.sanitizeFloat(0.5f, 0.0f, 1.0f, 0.0f));
        }

        @Test
        void nanReturnsDefault() {
            assertEquals(0.5f, InputSanitizer.sanitizeFloat(Float.NaN, 0.0f, 1.0f, 0.5f));
        }

        @Test
        void infinityReturnsDefault() {
            assertEquals(0.5f, InputSanitizer.sanitizeFloat(Float.POSITIVE_INFINITY, 0.0f, 1.0f, 0.5f));
        }

        @Test
        void valueClamped() {
            assertEquals(1.0f, InputSanitizer.sanitizeFloat(5.0f, 0.0f, 1.0f, 0.5f));
            assertEquals(0.0f, InputSanitizer.sanitizeFloat(-5.0f, 0.0f, 1.0f, 0.5f));
        }
    }

    @Nested
    @DisplayName("sanitizeInt")
    class SanitizeIntTests {
        @Test
        void normalValuePassesThrough() {
            assertEquals(5, InputSanitizer.sanitizeInt(5, 0, 15, 10));
        }

        @Test
        void valueBelowMinClamped() {
            assertEquals(0, InputSanitizer.sanitizeInt(-5, 0, 15, 10));
        }

        @Test
        void valueAboveMaxClamped() {
            assertEquals(15, InputSanitizer.sanitizeInt(20, 0, 15, 10));
        }

        @Test
        void valueAtBoundsPassesThrough() {
            assertEquals(0, InputSanitizer.sanitizeInt(0, 0, 15, 10));
            assertEquals(15, InputSanitizer.sanitizeInt(15, 0, 15, 10));
        }
    }

    @Nested
    @DisplayName("Convenience methods")
    class ConvenienceTests {
        @Test
        void sanitizeCoordinate() {
            assertEquals(0.5, InputSanitizer.sanitizeCoordinate(0.5));
            assertEquals(0.0, InputSanitizer.sanitizeCoordinate(-1.0));
            assertEquals(1.0, InputSanitizer.sanitizeCoordinate(2.0));
            assertEquals(0.5, InputSanitizer.sanitizeCoordinate(Double.NaN));
        }

        @Test
        void sanitizeScale() {
            assertEquals(1.0f, InputSanitizer.sanitizeScale(1.0f));
            assertEquals(0.0f, InputSanitizer.sanitizeScale(-1.0f));
            assertEquals(4.0f, InputSanitizer.sanitizeScale(10.0f));
            assertEquals(0.5f, InputSanitizer.sanitizeScale(Float.NaN));
        }

        @Test
        void sanitizeRotation() {
            assertEquals(90.0f, InputSanitizer.sanitizeRotation(90.0f));
            assertEquals(-360.0f, InputSanitizer.sanitizeRotation(-500.0f));
            assertEquals(360.0f, InputSanitizer.sanitizeRotation(500.0f));
            assertEquals(0.0f, InputSanitizer.sanitizeRotation(Float.NaN));
        }

        @Test
        void sanitizeBrightness() {
            assertEquals(10, InputSanitizer.sanitizeBrightness(10));
            assertEquals(0, InputSanitizer.sanitizeBrightness(-5));
            assertEquals(15, InputSanitizer.sanitizeBrightness(20));
        }

        @Test
        void sanitizeInterpolation() {
            assertEquals(5, InputSanitizer.sanitizeInterpolation(5));
            assertEquals(0, InputSanitizer.sanitizeInterpolation(-1));
            assertEquals(100, InputSanitizer.sanitizeInterpolation(200));
        }

        @Test
        void sanitizeBandValue() {
            assertEquals(0.5, InputSanitizer.sanitizeBandValue(0.5));
            assertEquals(0.0, InputSanitizer.sanitizeBandValue(-0.3));
            assertEquals(1.0, InputSanitizer.sanitizeBandValue(1.5));
            assertEquals(0.0, InputSanitizer.sanitizeBandValue(Double.NaN));
        }

        @Test
        void sanitizeAmplitude() {
            assertEquals(1.0, InputSanitizer.sanitizeAmplitude(1.0));
            assertEquals(0.0, InputSanitizer.sanitizeAmplitude(-1.0));
            assertEquals(5.0, InputSanitizer.sanitizeAmplitude(10.0));
            assertEquals(0.0, InputSanitizer.sanitizeAmplitude(Double.NaN));
        }
    }
}
