package com.audioviz.render;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RenderTimingPolicyTest {

    @Test
    void beatAndTickDurationsAreFormulaDerived() {
        RenderTimingPolicy policy = new RenderTimingPolicy(1, 150_000_000L, 120_000_000L, 0.60);

        assertEquals(300_000_000L, policy.beatCooldownNanos(120.0));
        assertEquals(3, policy.durationToTicks(150_000_000L, 50_000_000L));
        assertEquals(4, policy.durationToTicks(151_000_000L, 50_000_000L));
    }

    @Test
    void invalidBpmUsesTheMinimumCooldown() {
        RenderTimingPolicy policy = new RenderTimingPolicy(1, 150_000_000L, 120_000_000L, 0.60);

        assertEquals(120_000_000L, policy.beatCooldownNanos(0.0));
        assertEquals(120_000_000L, policy.beatCooldownNanos(Double.NaN));
    }

    @Test
    void measuredTickDurationMustBePositive() {
        RenderTimingPolicy policy = new RenderTimingPolicy(1, 150_000_000L, 120_000_000L, 0.60);

        assertEquals(0, policy.durationToTicks(0L, 50_000_000L));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.durationToTicks(1L, 0L));
    }

    @Test
    void configurationUsesMillisecondsAndClampsInterpolationOnce() {
        FileConfiguration config = new YamlConfiguration();
        config.set("defaults.interpolation_duration", 101);
        config.set("defaults.glow_duration_ms", 150);
        config.set("defaults.beat_cooldown_min_ms", 120);
        config.set("defaults.beat_cooldown_fraction", 0.60);

        RenderTimingPolicy policy = RenderTimingPolicy.from(config);

        assertEquals(RenderProtocolLimits.INTERPOLATION_TICKS_MAX, policy.interpolationTicks());
        assertEquals(150_000_000L, policy.glowDurationNanos());
        assertEquals(120_000_000L, policy.minimumBeatCooldownNanos());
        assertEquals(0.60, policy.beatCooldownFraction());
    }
}
