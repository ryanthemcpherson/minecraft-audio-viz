package com.audioviz.protocol;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BeatProjectionUtilTest {

    private Map<String, Long> cooldownMap;

    @BeforeEach
    void setUp() {
        cooldownMap = new HashMap<>();
    }

    @Nested
    @DisplayName("Explicit Beat Pass-Through")
    class ExplicitBeat {

        @Test
        @DisplayName("explicit beat returns true with clamped intensity")
        void explicitBeatReturnsTrue() {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", true, 0.75, 120, 0.9, 0.5, cooldownMap);
            assertTrue(result.isBeat());
            assertEquals(0.75, result.beatIntensity(), 0.001);
        }

        @Test
        @DisplayName("explicit beat clamps intensity > 1 to 1")
        void clampsHighIntensity() {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", true, 1.5, 120, 0.9, 0.5, cooldownMap);
            assertTrue(result.isBeat());
            assertEquals(1.0, result.beatIntensity(), 0.001);
        }

        @Test
        @DisplayName("explicit beat clamps negative intensity to 0")
        void clampsNegativeIntensity() {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", true, -0.5, 120, 0.9, 0.5, cooldownMap);
            assertTrue(result.isBeat());
            assertEquals(0.0, result.beatIntensity(), 0.001);
        }

        @Test
        @DisplayName("explicit beat clamps NaN intensity to 0")
        void clampsNanIntensity() {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", true, Double.NaN, 120, 0.9, 0.5, cooldownMap);
            assertTrue(result.isBeat());
            assertEquals(0.0, result.beatIntensity(), 0.001);
        }

        @Test
        @DisplayName("explicit beat updates cooldown map")
        void updatesCooldownMap() {
            BeatProjectionUtil.projectBeat(
                "zone1", true, 0.8, 120, 0.9, 0.5, cooldownMap);
            assertTrue(cooldownMap.containsKey("zone1"));
            assertTrue(cooldownMap.get("zone1") > 0);
        }
    }

    @Nested
    @DisplayName("Confidence and BPM Thresholds")
    class Thresholds {

        @ParameterizedTest
        @ValueSource(doubles = {0.0, 0.1, 0.3, 0.59})
        @DisplayName("low tempo confidence rejects synthesis")
        void lowConfidenceRejects(double confidence) {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, confidence, 0.05, cooldownMap);
            assertFalse(result.isBeat());
            assertEquals(0.0, result.beatIntensity());
        }

        @Test
        @DisplayName("confidence at threshold allows synthesis")
        void confidenceAtThreshold() {
            // 0.60 is the threshold — should pass confidence check
            // Phase 0.05 is within edge window, no cooldown
            var result = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 0.60, 0.05, cooldownMap);
            assertTrue(result.isBeat());
        }

        @ParameterizedTest
        @ValueSource(doubles = {0.0, 30.0, 59.9})
        @DisplayName("low BPM rejects synthesis")
        void lowBpmRejects(double bpm) {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, bpm, 0.9, 0.05, cooldownMap);
            assertFalse(result.isBeat());
        }

        @Test
        @DisplayName("BPM at threshold allows synthesis")
        void bpmAtThreshold() {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 60.0, 0.9, 0.05, cooldownMap);
            assertTrue(result.isBeat());
        }
    }

    @Nested
    @DisplayName("Phase Edge Detection")
    class PhaseEdge {

        @ParameterizedTest
        @CsvSource({"0.0", "0.05", "0.12"})
        @DisplayName("phase near 0 (within edge window) triggers")
        void phaseNearZero(double phase) {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 0.9, phase, cooldownMap);
            assertTrue(result.isBeat(), "Phase " + phase + " should be within edge window");
        }

        @ParameterizedTest
        @CsvSource({"0.88", "0.95", "1.0"})
        @DisplayName("phase near 1 (within edge window) triggers")
        void phaseNearOne(double phase) {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 0.9, phase, cooldownMap);
            assertTrue(result.isBeat(), "Phase " + phase + " should be within edge window");
        }

        @ParameterizedTest
        @CsvSource({"0.13", "0.3", "0.5", "0.7", "0.87"})
        @DisplayName("phase in middle rejects synthesis")
        void phaseMiddle(double phase) {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 0.9, phase, cooldownMap);
            assertFalse(result.isBeat(), "Phase " + phase + " should NOT be in edge window");
        }
    }

    @Nested
    @DisplayName("Cooldown Tracking")
    class Cooldown {

        @Test
        @DisplayName("second synth beat within cooldown is rejected")
        void cooldownRejects() {
            // First call succeeds
            var first = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 0.9, 0.05, cooldownMap);
            assertTrue(first.isBeat());

            // Immediate second call — within cooldown
            var second = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 0.9, 0.05, cooldownMap);
            assertFalse(second.isBeat());
        }

        @Test
        @DisplayName("different zones have independent cooldowns")
        void independentZones() {
            var z1 = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 0.9, 0.05, cooldownMap);
            var z2 = BeatProjectionUtil.projectBeat(
                "zone2", false, 0.0, 120, 0.9, 0.05, cooldownMap);
            assertTrue(z1.isBeat());
            assertTrue(z2.isBeat());
        }

        @Test
        @DisplayName("explicit beat resets cooldown for zone")
        void explicitResetsCooldown() {
            // Synthesize a beat (sets cooldown)
            BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 0.9, 0.05, cooldownMap);
            // Explicit beat overrides cooldown
            var result = BeatProjectionUtil.projectBeat(
                "zone1", true, 0.8, 120, 0.9, 0.5, cooldownMap);
            assertTrue(result.isBeat());
        }
    }

    @Nested
    @DisplayName("Synthesized Beat Intensity")
    class SynthIntensity {

        @Test
        @DisplayName("synth intensity is at least SYNTH_BEAT_MIN_INTENSITY")
        void minimumIntensity() {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 0.60, 0.05, cooldownMap);
            assertTrue(result.isBeat());
            assertTrue(result.beatIntensity() >= BeatProjectionUtil.SYNTH_BEAT_MIN_INTENSITY);
        }

        @Test
        @DisplayName("synth intensity scales with tempo confidence")
        void scalesWithConfidence() {
            var low = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 0.65, 0.05, cooldownMap);
            cooldownMap.clear();
            var high = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 1.0, 0.05, cooldownMap);
            assertTrue(high.beatIntensity() >= low.beatIntensity());
        }

        @Test
        @DisplayName("synth intensity clamped to [0, 1]")
        void clampedToRange() {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 1.0, 0.05, cooldownMap);
            assertTrue(result.beatIntensity() >= 0.0);
            assertTrue(result.beatIntensity() <= 1.0);
        }
    }

    @Nested
    @DisplayName("Constants")
    class Constants {

        @Test
        @DisplayName("constants have expected values")
        void expectedValues() {
            assertEquals(0.60, BeatProjectionUtil.MIN_PHASE_ASSIST_CONFIDENCE);
            assertEquals(60.0, BeatProjectionUtil.MIN_PHASE_ASSIST_BPM);
            assertEquals(0.12, BeatProjectionUtil.PHASE_EDGE_WINDOW);
            assertEquals(0.25, BeatProjectionUtil.SYNTH_BEAT_MIN_INTENSITY);
            assertEquals(0.60, BeatProjectionUtil.SYNTH_BEAT_COOLDOWN_FRACTION);
            assertEquals(120L, BeatProjectionUtil.SYNTH_BEAT_COOLDOWN_MIN_MS);
        }
    }
}
