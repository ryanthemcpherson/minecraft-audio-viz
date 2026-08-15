package com.audioviz.render;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Objects;

/**
 * Configured renderer timings and their conversions to the server tick clock.
 */
public record RenderTimingPolicy(
        int interpolationTicks,
        long glowDurationNanos,
        long minimumBeatCooldownNanos,
        double beatCooldownFraction
) {
    private static final int DEFAULT_INTERPOLATION_TICKS = 1;
    private static final long DEFAULT_GLOW_DURATION_MILLIS = 150L;
    private static final long DEFAULT_MINIMUM_BEAT_COOLDOWN_MILLIS = 120L;
    private static final double DEFAULT_BEAT_COOLDOWN_FRACTION = 0.60;
    private static final long NANOS_PER_MILLISECOND = 1_000_000L;

    public RenderTimingPolicy {
        if (interpolationTicks < RenderProtocolLimits.INTERPOLATION_TICKS_MIN
                || interpolationTicks > RenderProtocolLimits.INTERPOLATION_TICKS_MAX) {
            throw new IllegalArgumentException("interpolationTicks");
        }
        if (glowDurationNanos <= 0) {
            throw new IllegalArgumentException("glowDurationNanos");
        }
        if (minimumBeatCooldownNanos <= 0) {
            throw new IllegalArgumentException("minimumBeatCooldownNanos");
        }
        if (!Double.isFinite(beatCooldownFraction)
                || beatCooldownFraction < RenderProtocolLimits.UNIT_MIN
                || beatCooldownFraction > RenderProtocolLimits.UNIT_MAX) {
            throw new IllegalArgumentException("beatCooldownFraction");
        }
    }

    public static RenderTimingPolicy from(FileConfiguration config) {
        Objects.requireNonNull(config, "config");
        return new RenderTimingPolicy(
                clamp(config.getInt("defaults.interpolation_duration", DEFAULT_INTERPOLATION_TICKS),
                        RenderProtocolLimits.INTERPOLATION_TICKS_MIN,
                        RenderProtocolLimits.INTERPOLATION_TICKS_MAX),
                millisecondsToNanos(clampPositive(config.getLong(
                        "defaults.glow_duration_ms", DEFAULT_GLOW_DURATION_MILLIS))),
                millisecondsToNanos(clampPositive(config.getLong(
                        "defaults.beat_cooldown_min_ms", DEFAULT_MINIMUM_BEAT_COOLDOWN_MILLIS))),
                clamp(config.getDouble("defaults.beat_cooldown_fraction", DEFAULT_BEAT_COOLDOWN_FRACTION),
                        RenderProtocolLimits.UNIT_MIN,
                        RenderProtocolLimits.UNIT_MAX));
    }

    public long beatCooldownNanos(double bpm) {
        if (!Double.isFinite(bpm) || bpm <= 0.0) {
            return minimumBeatCooldownNanos;
        }
        long period = Math.round(60_000_000_000.0 / bpm);
        return Math.max(minimumBeatCooldownNanos, Math.round(period * beatCooldownFraction));
    }

    public int durationToTicks(long durationNanos, long measuredTickNanos) {
        if (durationNanos <= 0) {
            return 0;
        }
        if (measuredTickNanos <= 0) {
            throw new IllegalArgumentException("measuredTickNanos");
        }
        return Math.toIntExact(Math.floorDiv(durationNanos - 1, measuredTickNanos) + 1);
    }

    private static long millisecondsToNanos(long milliseconds) {
        return Math.multiplyExact(milliseconds, NANOS_PER_MILLISECOND);
    }

    private static long clampPositive(long value) {
        return Math.max(1L, value);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) {
            return DEFAULT_BEAT_COOLDOWN_FRACTION;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}
