package com.audioviz.render;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Objects;

/**
 * Authoritative domain and resource limits for renderer protocol data.
 */
public record RenderProtocolLimits(
        int maxZones,
        int maxEntitiesPerZone,
        int maxParticlesPerTick,
        int decoderThreads
) {
    public static final int BAND_COUNT = 5;
    public static final double UNIT_MIN = 0.0;
    public static final double UNIT_MAX = 1.0;
    public static final float ENTITY_SCALE_MAX = 4.0f;
    public static final double BPM_MAX = 300.0;
    public static final int BRIGHTNESS_MIN = 0;
    public static final int BRIGHTNESS_MAX = 15;
    public static final int INTERPOLATION_TICKS_MIN = 0;
    public static final int INTERPOLATION_TICKS_MAX = 100;

    private static final int MINIMUM_RESOURCE_LIMIT = 1;
    private static final int DEFAULT_MAX_ZONES = 32;
    private static final int DEFAULT_MAX_ENTITIES_PER_ZONE = 256;
    private static final int DEFAULT_MAX_PARTICLES_PER_TICK = 2_000;
    private static final int DEFAULT_DECODER_THREADS = 2;

    public RenderProtocolLimits {
        validatePositive("maxZones", maxZones);
        validatePositive("maxEntitiesPerZone", maxEntitiesPerZone);
        validatePositive("maxParticlesPerTick", maxParticlesPerTick);
        validatePositive("decoderThreads", decoderThreads);
    }

    public static RenderProtocolLimits from(FileConfiguration config) {
        Objects.requireNonNull(config, "config");
        return new RenderProtocolLimits(
                clampPositive(config.getInt("performance.max_zones", DEFAULT_MAX_ZONES)),
                clampPositive(config.getInt("performance.max_entities_per_zone", DEFAULT_MAX_ENTITIES_PER_ZONE)),
                clampPositive(config.getInt("performance.max_particles_per_tick", DEFAULT_MAX_PARTICLES_PER_TICK)),
                clampPositive(config.getInt("performance.render_decoder_threads", DEFAULT_DECODER_THREADS)));
    }

    public int snapshotSlotCount() {
        return Math.addExact(decoderThreads, 2);
    }

    private static int clampPositive(int value) {
        return Math.max(MINIMUM_RESOURCE_LIMIT, value);
    }

    private static void validatePositive(String name, int value) {
        if (value < MINIMUM_RESOURCE_LIMIT) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
