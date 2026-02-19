package com.audioviz.protocol;

/**
 * Shared input validation utilities for WebSocket message data.
 *
 * All methods clamp values to safe ranges and replace NaN/Infinity with defaults,
 * following a "clamp, don't reject" strategy for graceful degradation.
 */
public final class InputSanitizer {

    private InputSanitizer() {
        // Utility class
    }

    /**
     * Sanitize a double value: reject NaN/Infinity, clamp to [min, max].
     */
    public static double sanitizeDouble(double value, double min, double max, double defaultVal) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return defaultVal;
        }
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Sanitize a float value: reject NaN/Infinity, clamp to [min, max].
     */
    public static float sanitizeFloat(float value, float min, float max, float defaultVal) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return defaultVal;
        }
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Sanitize an int value: clamp to [min, max].
     */
    public static int sanitizeInt(int value, int min, int max, int defaultVal) {
        if (value < min || value > max) {
            return Math.max(min, Math.min(max, value));
        }
        return value;
    }

    // --- Convenience constants for common field ranges ---

    /** Entity local coordinates (normalized 0-1). */
    public static double sanitizeCoordinate(double value) {
        return sanitizeDouble(value, 0.0, 1.0, 0.5);
    }

    /** Entity scale (0-4, per entity-update.schema.json). */
    public static float sanitizeScale(float value) {
        return sanitizeFloat(value, 0.0f, 4.0f, 0.5f);
    }

    /** Entity rotation in degrees. */
    public static float sanitizeRotation(float value) {
        return sanitizeFloat(value, -360.0f, 360.0f, 0.0f);
    }

    /** Minecraft block light level (0-15). */
    public static int sanitizeBrightness(int value) {
        return sanitizeInt(value, 0, 15, 15);
    }

    /** Interpolation duration in ticks. */
    public static int sanitizeInterpolation(int value) {
        return sanitizeInt(value, 0, 100, 3);
    }

    /** Audio band value (normalized 0-1). */
    public static double sanitizeBandValue(double value) {
        return sanitizeDouble(value, 0.0, 1.0, 0.0);
    }

    /** Audio amplitude (non-negative). */
    public static double sanitizeAmplitude(double value) {
        return sanitizeDouble(value, 0.0, 5.0, 0.0);
    }
}
