package com.audioviz.stages;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StageZoneConfig - pure data class, no Bukkit dependencies.
 */
class StageZoneConfigTest {

    @Nested
    @DisplayName("Default Constructor")
    class DefaultConstructor {

        @Test
        @DisplayName("sets default pattern to spectrum")
        void defaultPattern() {
            StageZoneConfig config = new StageZoneConfig();
            assertEquals("spectrum", config.getPattern());
        }

        @Test
        @DisplayName("sets default entity count to 16")
        void defaultEntityCount() {
            StageZoneConfig config = new StageZoneConfig();
            assertEquals(16, config.getEntityCount());
        }

        @Test
        @DisplayName("sets default render mode to entities")
        void defaultRenderMode() {
            StageZoneConfig config = new StageZoneConfig();
            assertEquals("entities", config.getRenderMode());
        }

        @Test
        @DisplayName("sets default block type to SEA_LANTERN")
        void defaultBlockType() {
            StageZoneConfig config = new StageZoneConfig();
            assertEquals("SEA_LANTERN", config.getBlockType());
        }

        @Test
        @DisplayName("sets default brightness to 15")
        void defaultBrightness() {
            StageZoneConfig config = new StageZoneConfig();
            assertEquals(15, config.getBrightness());
        }

        @Test
        @DisplayName("sets default glowOnBeat to false")
        void defaultGlowOnBeat() {
            StageZoneConfig config = new StageZoneConfig();
            assertFalse(config.isGlowOnBeat());
        }

        @Test
        @DisplayName("sets default intensity multiplier to 1.0")
        void defaultIntensityMultiplier() {
            StageZoneConfig config = new StageZoneConfig();
            assertEquals(1.0f, config.getIntensityMultiplier(), 1e-6);
        }
    }

    @Nested
    @DisplayName("Copy Constructor")
    class CopyConstructor {

        @Test
        @DisplayName("copies all fields from source")
        void copiesAllFields() {
            StageZoneConfig source = new StageZoneConfig();
            source.setPattern("wave");
            source.setEntityCount(64);
            source.setRenderMode("blocks");
            source.setBlockType("GLOWSTONE");
            source.setBrightness(10);
            source.setGlowOnBeat(true);
            source.setIntensityMultiplier(2.0f);

            StageZoneConfig copy = new StageZoneConfig(source);

            assertEquals("wave", copy.getPattern());
            assertEquals(64, copy.getEntityCount());
            assertEquals("blocks", copy.getRenderMode());
            assertEquals("GLOWSTONE", copy.getBlockType());
            assertEquals(10, copy.getBrightness());
            assertTrue(copy.isGlowOnBeat());
            assertEquals(2.0f, copy.getIntensityMultiplier(), 1e-6);
        }

        @Test
        @DisplayName("modifying copy does not affect source")
        void copyIsIndependent() {
            StageZoneConfig source = new StageZoneConfig();
            source.setPattern("laser");

            StageZoneConfig copy = new StageZoneConfig(source);
            copy.setPattern("aurora");

            assertEquals("laser", source.getPattern());
            assertEquals("aurora", copy.getPattern());
        }
    }

    @Nested
    @DisplayName("setEntityCount Clamping")
    class EntityCountClamping {

        @Test
        @DisplayName("clamps to minimum of 1")
        void clampsToMin() {
            StageZoneConfig config = new StageZoneConfig();
            config.setEntityCount(0);
            assertEquals(1, config.getEntityCount());
        }

        @Test
        @DisplayName("clamps negative values to 1")
        void clampsNegative() {
            StageZoneConfig config = new StageZoneConfig();
            config.setEntityCount(-100);
            assertEquals(1, config.getEntityCount());
        }

        @Test
        @DisplayName("clamps to maximum of 1000")
        void clampsToMax() {
            StageZoneConfig config = new StageZoneConfig();
            config.setEntityCount(5000);
            assertEquals(1000, config.getEntityCount());
        }

        @Test
        @DisplayName("accepts value within range")
        void acceptsValidValue() {
            StageZoneConfig config = new StageZoneConfig();
            config.setEntityCount(500);
            assertEquals(500, config.getEntityCount());
        }

        @Test
        @DisplayName("accepts boundary value 1")
        void acceptsBoundaryMin() {
            StageZoneConfig config = new StageZoneConfig();
            config.setEntityCount(1);
            assertEquals(1, config.getEntityCount());
        }

        @Test
        @DisplayName("accepts boundary value 1000")
        void acceptsBoundaryMax() {
            StageZoneConfig config = new StageZoneConfig();
            config.setEntityCount(1000);
            assertEquals(1000, config.getEntityCount());
        }
    }

    @Nested
    @DisplayName("setBrightness Clamping")
    class BrightnessClamping {

        @Test
        @DisplayName("clamps to minimum of 0")
        void clampsToMin() {
            StageZoneConfig config = new StageZoneConfig();
            config.setBrightness(-5);
            assertEquals(0, config.getBrightness());
        }

        @Test
        @DisplayName("clamps to maximum of 15")
        void clampsToMax() {
            StageZoneConfig config = new StageZoneConfig();
            config.setBrightness(20);
            assertEquals(15, config.getBrightness());
        }

        @Test
        @DisplayName("accepts value within range")
        void acceptsValidValue() {
            StageZoneConfig config = new StageZoneConfig();
            config.setBrightness(8);
            assertEquals(8, config.getBrightness());
        }

        @Test
        @DisplayName("accepts boundary value 0")
        void acceptsBoundaryMin() {
            StageZoneConfig config = new StageZoneConfig();
            config.setBrightness(0);
            assertEquals(0, config.getBrightness());
        }

        @Test
        @DisplayName("accepts boundary value 15")
        void acceptsBoundaryMax() {
            StageZoneConfig config = new StageZoneConfig();
            config.setBrightness(15);
            assertEquals(15, config.getBrightness());
        }
    }

    @Nested
    @DisplayName("setIntensityMultiplier Clamping")
    class IntensityMultiplierClamping {

        @Test
        @DisplayName("clamps to minimum of 0.1")
        void clampsToMin() {
            StageZoneConfig config = new StageZoneConfig();
            config.setIntensityMultiplier(0.0f);
            assertEquals(0.1f, config.getIntensityMultiplier(), 1e-6);
        }

        @Test
        @DisplayName("clamps negative to 0.1")
        void clampsNegative() {
            StageZoneConfig config = new StageZoneConfig();
            config.setIntensityMultiplier(-1.0f);
            assertEquals(0.1f, config.getIntensityMultiplier(), 1e-6);
        }

        @Test
        @DisplayName("clamps to maximum of 3.0")
        void clampsToMax() {
            StageZoneConfig config = new StageZoneConfig();
            config.setIntensityMultiplier(5.0f);
            assertEquals(3.0f, config.getIntensityMultiplier(), 1e-6);
        }

        @Test
        @DisplayName("accepts value within range")
        void acceptsValidValue() {
            StageZoneConfig config = new StageZoneConfig();
            config.setIntensityMultiplier(1.5f);
            assertEquals(1.5f, config.getIntensityMultiplier(), 1e-6);
        }

        @Test
        @DisplayName("accepts boundary value 0.1")
        void acceptsBoundaryMin() {
            StageZoneConfig config = new StageZoneConfig();
            config.setIntensityMultiplier(0.1f);
            assertEquals(0.1f, config.getIntensityMultiplier(), 1e-6);
        }

        @Test
        @DisplayName("accepts boundary value 3.0")
        void acceptsBoundaryMax() {
            StageZoneConfig config = new StageZoneConfig();
            config.setIntensityMultiplier(3.0f);
            assertEquals(3.0f, config.getIntensityMultiplier(), 1e-6);
        }
    }

    @Nested
    @DisplayName("Simple Setters")
    class SimpleSetters {

        @Test
        @DisplayName("setPattern stores value")
        void setPattern() {
            StageZoneConfig config = new StageZoneConfig();
            config.setPattern("aurora");
            assertEquals("aurora", config.getPattern());
        }

        @Test
        @DisplayName("setRenderMode stores value")
        void setRenderMode() {
            StageZoneConfig config = new StageZoneConfig();
            config.setRenderMode("blocks");
            assertEquals("blocks", config.getRenderMode());
        }

        @Test
        @DisplayName("setBlockType stores value")
        void setBlockType() {
            StageZoneConfig config = new StageZoneConfig();
            config.setBlockType("GLOWSTONE");
            assertEquals("GLOWSTONE", config.getBlockType());
        }

        @Test
        @DisplayName("setGlowOnBeat stores value")
        void setGlowOnBeat() {
            StageZoneConfig config = new StageZoneConfig();
            config.setGlowOnBeat(true);
            assertTrue(config.isGlowOnBeat());
        }
    }
}
