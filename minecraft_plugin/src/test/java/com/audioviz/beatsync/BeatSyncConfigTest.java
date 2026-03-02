package com.audioviz.beatsync;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class BeatSyncConfigTest {

    @Nested
    @DisplayName("Defaults")
    class Defaults {

        @Test
        @DisplayName("default config uses auto BPM")
        void defaultAutoBpm() {
            var config = new BeatSyncConfig();
            assertEquals(0.0, config.getManualBpm());
            assertTrue(config.isAutoBpm());
        }

        @Test
        @DisplayName("default phase offset is zero")
        void defaultPhaseOffset() {
            assertEquals(0.0, new BeatSyncConfig().getPhaseOffset());
        }

        @Test
        @DisplayName("default sensitivity multiplier is 1.0")
        void defaultSensitivity() {
            assertEquals(1.0, new BeatSyncConfig().getBeatThresholdMultiplier());
        }

        @Test
        @DisplayName("projection enabled by default")
        void defaultProjectionEnabled() {
            assertTrue(new BeatSyncConfig().isProjectionEnabled());
        }
    }

    @Nested
    @DisplayName("BPM Override")
    class BpmOverride {

        @Test
        @DisplayName("manual BPM overrides auto")
        void manualBpm() {
            var config = new BeatSyncConfig();
            config.setManualBpm(128.0);
            assertEquals(128.0, config.getManualBpm());
            assertFalse(config.isAutoBpm());
        }

        @Test
        @DisplayName("setting BPM to 0 returns to auto")
        void zeroResetsToAuto() {
            var config = new BeatSyncConfig();
            config.setManualBpm(128.0);
            config.setManualBpm(0);
            assertTrue(config.isAutoBpm());
        }

        @Test
        @DisplayName("negative BPM clamped to 0")
        void negativeClamped() {
            var config = new BeatSyncConfig();
            config.setManualBpm(-10);
            assertEquals(0, config.getManualBpm());
        }

        @Test
        @DisplayName("BPM clamped to 300")
        void maxClamped() {
            var config = new BeatSyncConfig();
            config.setManualBpm(500);
            assertEquals(300, config.getManualBpm());
        }
    }

    @Nested
    @DisplayName("Phase Offset")
    class PhaseOffset {

        @Test
        @DisplayName("phase offset clamped to -0.5..0.5")
        void clamped() {
            var config = new BeatSyncConfig();
            config.setPhaseOffset(0.8);
            assertEquals(0.5, config.getPhaseOffset());
            config.setPhaseOffset(-0.8);
            assertEquals(-0.5, config.getPhaseOffset());
        }

        @Test
        @DisplayName("applyPhaseOffset wraps correctly")
        void wraps() {
            assertEquals(0.2, BeatSyncConfig.applyPhaseOffset(0.0, 0.2), 0.001);
            assertEquals(0.8, BeatSyncConfig.applyPhaseOffset(0.0, -0.2), 0.001);
            assertEquals(0.1, BeatSyncConfig.applyPhaseOffset(0.9, 0.2), 0.001);
        }
    }

    @Nested
    @DisplayName("Sensitivity")
    class Sensitivity {

        @Test
        @DisplayName("sensitivity clamped to 0.1..5.0")
        void clamped() {
            var config = new BeatSyncConfig();
            config.setBeatThresholdMultiplier(0.01);
            assertEquals(0.1, config.getBeatThresholdMultiplier());
            config.setBeatThresholdMultiplier(10.0);
            assertEquals(5.0, config.getBeatThresholdMultiplier());
        }
    }
}
