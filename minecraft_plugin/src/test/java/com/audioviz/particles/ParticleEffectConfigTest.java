package com.audioviz.particles;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ParticleEffectConfig - pure data class with clamping logic.
 */
class ParticleEffectConfigTest {

    @Nested
    @DisplayName("Default Constructor")
    class DefaultConstructor {

        @Test
        @DisplayName("enabled by default")
        void enabledByDefault() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            assertTrue(config.isEnabled());
        }

        @Test
        @DisplayName("default intensity is 1.0")
        void defaultIntensity() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            assertEquals(1.0, config.getIntensity(), 1e-10);
        }

        @Test
        @DisplayName("default effect intensity is 1.0")
        void defaultEffectIntensity() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            assertEquals(1.0, config.getEffectIntensity(), 1e-10);
        }

        @Test
        @DisplayName("default max particles per tick")
        void defaultMaxParticlesPerTick() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            assertEquals(ParticleEffectConfig.DEFAULT_MAX_PARTICLES_PER_TICK,
                config.getMaxParticlesPerTick());
        }

        @Test
        @DisplayName("default beat threshold is 0.3")
        void defaultBeatThreshold() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            assertEquals(0.3, config.getBeatThreshold(), 1e-10);
        }
    }

    @Nested
    @DisplayName("Intensity Constructor")
    class IntensityConstructor {

        @Test
        @DisplayName("sets both intensity and effect intensity")
        void setsBothIntensities() {
            ParticleEffectConfig config = new ParticleEffectConfig(0.75);
            assertEquals(0.75, config.getIntensity(), 1e-10);
            assertEquals(0.75, config.getEffectIntensity(), 1e-10);
        }
    }

    @Nested
    @DisplayName("setIntensity Clamping")
    class IntensityClamping {

        @Test
        @DisplayName("accepts value in range")
        void acceptsValid() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            config.setIntensity(1.5);
            assertEquals(1.5, config.getIntensity(), 1e-10);
        }

        @Test
        @DisplayName("clamps to minimum 0")
        void clampsToMin() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            config.setIntensity(-0.5);
            assertEquals(0.0, config.getIntensity(), 1e-10);
        }

        @Test
        @DisplayName("clamps to maximum 2.0")
        void clampsToMax() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            config.setIntensity(3.0);
            assertEquals(2.0, config.getIntensity(), 1e-10);
        }

        @Test
        @DisplayName("accepts boundary 0")
        void acceptsBoundaryMin() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            config.setIntensity(0.0);
            assertEquals(0.0, config.getIntensity(), 1e-10);
        }

        @Test
        @DisplayName("accepts boundary 2.0")
        void acceptsBoundaryMax() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            config.setIntensity(2.0);
            assertEquals(2.0, config.getIntensity(), 1e-10);
        }
    }

    @Nested
    @DisplayName("setEffectIntensity Clamping")
    class EffectIntensityClamping {

        @Test
        @DisplayName("clamps to 0..2.0 range")
        void clampsRange() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            config.setEffectIntensity(-1.0);
            assertEquals(0.0, config.getEffectIntensity(), 1e-10);
            config.setEffectIntensity(5.0);
            assertEquals(2.0, config.getEffectIntensity(), 1e-10);
        }
    }

    @Nested
    @DisplayName("setMaxParticlesPerTick Clamping")
    class MaxParticlesClamping {

        @Test
        @DisplayName("accepts value in range")
        void acceptsValid() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            config.setMaxParticlesPerTick(200);
            assertEquals(200, config.getMaxParticlesPerTick());
        }

        @Test
        @DisplayName("clamps to minimum 1")
        void clampsToMin() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            config.setMaxParticlesPerTick(0);
            assertEquals(1, config.getMaxParticlesPerTick());
        }

        @Test
        @DisplayName("clamps negative to 1")
        void clampsNegative() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            config.setMaxParticlesPerTick(-50);
            assertEquals(1, config.getMaxParticlesPerTick());
        }

        @Test
        @DisplayName("clamps to absolute max")
        void clampsToAbsMax() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            config.setMaxParticlesPerTick(9999);
            assertEquals(ParticleEffectConfig.ABSOLUTE_MAX_PARTICLES_PER_TICK,
                config.getMaxParticlesPerTick());
        }

        @Test
        @DisplayName("accepts boundary 1")
        void acceptsBoundaryMin() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            config.setMaxParticlesPerTick(1);
            assertEquals(1, config.getMaxParticlesPerTick());
        }

        @Test
        @DisplayName("accepts boundary at absolute max")
        void acceptsBoundaryMax() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            config.setMaxParticlesPerTick(ParticleEffectConfig.ABSOLUTE_MAX_PARTICLES_PER_TICK);
            assertEquals(ParticleEffectConfig.ABSOLUTE_MAX_PARTICLES_PER_TICK,
                config.getMaxParticlesPerTick());
        }
    }

    @Nested
    @DisplayName("setBeatThreshold Clamping")
    class BeatThresholdClamping {

        @Test
        @DisplayName("accepts value in range")
        void acceptsValid() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            config.setBeatThreshold(0.5);
            assertEquals(0.5, config.getBeatThreshold(), 1e-10);
        }

        @Test
        @DisplayName("clamps to minimum 0")
        void clampsToMin() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            config.setBeatThreshold(-0.3);
            assertEquals(0.0, config.getBeatThreshold(), 1e-10);
        }

        @Test
        @DisplayName("clamps to maximum 1.0")
        void clampsToMax() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            config.setBeatThreshold(2.0);
            assertEquals(1.0, config.getBeatThreshold(), 1e-10);
        }
    }

    @Nested
    @DisplayName("clampParticleCount")
    class ClampParticleCount {

        @Test
        @DisplayName("scales by both intensities")
        void scalesByIntensities() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            config.setIntensity(0.5);
            config.setEffectIntensity(0.5);
            // 100 * 0.5 * 0.5 = 25
            assertEquals(25, config.clampParticleCount(100));
        }

        @Test
        @DisplayName("clamps to MAX_PARTICLES_PER_EFFECT")
        void clampsToMaxPerEffect() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            config.setIntensity(2.0);
            config.setEffectIntensity(2.0);
            // 100 * 2.0 * 2.0 = 400, capped at MAX_PARTICLES_PER_EFFECT
            int result = config.clampParticleCount(100);
            assertEquals(ParticleEffectConfig.MAX_PARTICLES_PER_EFFECT, result);
        }

        @Test
        @DisplayName("zero intensity returns zero")
        void zeroIntensityReturnsZero() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            config.setIntensity(0.0);
            assertEquals(0, config.clampParticleCount(100));
        }

        @Test
        @DisplayName("identity at default intensities with small count")
        void identityAtDefaults() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            // 10 * 1.0 * 1.0 = 10
            assertEquals(10, config.clampParticleCount(10));
        }
    }

    @Nested
    @DisplayName("setEnabled")
    class SetEnabled {

        @Test
        @DisplayName("can disable")
        void canDisable() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            config.setEnabled(false);
            assertFalse(config.isEnabled());
        }

        @Test
        @DisplayName("can re-enable")
        void canReEnable() {
            ParticleEffectConfig config = new ParticleEffectConfig();
            config.setEnabled(false);
            config.setEnabled(true);
            assertTrue(config.isEnabled());
        }
    }

    @Nested
    @DisplayName("Constants")
    class Constants {

        @Test
        @DisplayName("ABSOLUTE_MAX is 500")
        void absoluteMax() {
            assertEquals(500, ParticleEffectConfig.ABSOLUTE_MAX_PARTICLES_PER_TICK);
        }

        @Test
        @DisplayName("DEFAULT_MAX is 100")
        void defaultMax() {
            assertEquals(100, ParticleEffectConfig.DEFAULT_MAX_PARTICLES_PER_TICK);
        }

        @Test
        @DisplayName("MAX_PER_EFFECT is 50")
        void maxPerEffect() {
            assertEquals(50, ParticleEffectConfig.MAX_PARTICLES_PER_EFFECT);
        }

        @Test
        @DisplayName("MIN_BEAT_COOLDOWN is 150ms")
        void minBeatCooldown() {
            assertEquals(150L, ParticleEffectConfig.MIN_BEAT_COOLDOWN_MS);
        }
    }
}
