package com.audioviz.protocol;

import com.audioviz.render.RenderProtocolLimits;

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
     * Values outside the range are clamped (not replaced with defaultVal) for consistency
     * with the double/float sanitizers. The defaultVal is reserved for truly invalid states
     * if needed in future, but ints cannot be NaN so clamping always applies.
     */
    public static int sanitizeInt(int value, int min, int max, int defaultVal) {
        return Math.max(min, Math.min(max, value));
    }

    // --- Convenience constants for common field ranges ---

    /** Entity local coordinates (normalized 0-1). */
    public static double sanitizeCoordinate(double value) {
        return sanitizeDouble(value, RenderProtocolLimits.UNIT_MIN, RenderProtocolLimits.UNIT_MAX, 0.5);
    }

    /** Entity scale (0-4, per entity-update.schema.json). */
    public static float sanitizeScale(float value) {
        return sanitizeFloat(value, (float) RenderProtocolLimits.UNIT_MIN,
                RenderProtocolLimits.ENTITY_SCALE_MAX, 0.5f);
    }

    /** Entity rotation in degrees. */
    public static float sanitizeRotation(float value) {
        return sanitizeFloat(value, -360.0f, 360.0f, 0.0f);
    }

    /** Minecraft block light level (0-15). */
    public static int sanitizeBrightness(int value) {
        return sanitizeInt(value, RenderProtocolLimits.BRIGHTNESS_MIN,
                RenderProtocolLimits.BRIGHTNESS_MAX, RenderProtocolLimits.BRIGHTNESS_MAX);
    }

    /** Interpolation duration in ticks. */
    public static int sanitizeInterpolation(int value) {
        return sanitizeInt(value, RenderProtocolLimits.INTERPOLATION_TICKS_MIN,
                RenderProtocolLimits.INTERPOLATION_TICKS_MAX, 3);
    }

    /** Audio band value (normalized 0-1). */
    public static double sanitizeBandValue(double value) {
        return sanitizeDouble(value, RenderProtocolLimits.UNIT_MIN, RenderProtocolLimits.UNIT_MAX,
                RenderProtocolLimits.UNIT_MIN);
    }

    /** Audio amplitude (normalized 0-1). */
    public static double sanitizeAmplitude(double value) {
        return sanitizeDouble(value, RenderProtocolLimits.UNIT_MIN, RenderProtocolLimits.UNIT_MAX,
                RenderProtocolLimits.UNIT_MIN);
    }

    /** Beat intensity (normalized 0-1). */
    public static double sanitizeBeatIntensity(double value) {
        return sanitizeDouble(value, RenderProtocolLimits.UNIT_MIN, RenderProtocolLimits.UNIT_MAX,
                RenderProtocolLimits.UNIT_MIN);
    }

    /** Beats per minute (0-300). */
    public static double sanitizeBpm(double value) {
        return sanitizeDouble(value, RenderProtocolLimits.UNIT_MIN, RenderProtocolLimits.BPM_MAX,
                RenderProtocolLimits.UNIT_MIN);
    }
}
