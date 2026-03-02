package com.audioviz.sequence;

import org.junit.jupiter.api.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SequenceTest {

    @Nested
    @DisplayName("SequenceStep")
    class StepTests {
        @Test
        void storesPatternsAndDuration() {
            var step = new SequenceStep(
                Map.of("zone1", "bmp_plasma", "zone2", "bmp_fire"),
                200, "dissolve", 40);
            assertEquals(2, step.zonePatterns().size());
            assertEquals("bmp_plasma", step.zonePatterns().get("zone1"));
            assertEquals(200, step.durationTicks());
            assertEquals("dissolve", step.transitionId());
            assertEquals(40, step.transitionDuration());
        }

        @Test
        void zeroMeansUseDefault() {
            var step = new SequenceStep(Map.of("zone1", "bmp_bars"), 0, null, 0);
            assertEquals(0, step.durationTicks());
            assertNull(step.transitionId());
        }
    }

    @Nested
    @DisplayName("Sequence")
    class SequenceTests {
        @Test
        void constructorAndDefaults() {
            var seq = new Sequence("my_sequence");
            assertEquals("my_sequence", seq.getName());
            assertEquals(PlaybackMode.LOOP, seq.getMode());
            assertTrue(seq.getSteps().isEmpty());
            assertEquals(600, seq.getDefaultStepDuration());
            assertEquals("crossfade", seq.getDefaultTransition());
            assertEquals(20, seq.getDefaultTransitionDuration());
        }

        @Test
        void addAndRemoveSteps() {
            var seq = new Sequence("test");
            var step = new SequenceStep(Map.of("z", "p"), 100, null, 0);
            seq.addStep(step);
            assertEquals(1, seq.getSteps().size());
            seq.removeStep(0);
            assertTrue(seq.getSteps().isEmpty());
        }

        @Test
        void effectiveDurationUsesDefault() {
            var seq = new Sequence("test");
            seq.setDefaultStepDuration(400);
            var step = new SequenceStep(Map.of("z", "p"), 0, null, 0);
            assertEquals(400, seq.getEffectiveDuration(step));
        }

        @Test
        void effectiveDurationUsesOverride() {
            var seq = new Sequence("test");
            seq.setDefaultStepDuration(400);
            var step = new SequenceStep(Map.of("z", "p"), 200, null, 0);
            assertEquals(200, seq.getEffectiveDuration(step));
        }

        @Test
        void effectiveTransitionUsesDefault() {
            var seq = new Sequence("test");
            var step = new SequenceStep(Map.of("z", "p"), 0, null, 0);
            assertEquals("crossfade", seq.getEffectiveTransition(step));
        }

        @Test
        void effectiveTransitionUsesOverride() {
            var seq = new Sequence("test");
            var step = new SequenceStep(Map.of("z", "p"), 0, "wipe_left", 0);
            assertEquals("wipe_left", seq.getEffectiveTransition(step));
        }
    }

    @Nested
    @DisplayName("PlaybackMode")
    class PlaybackModeTests {
        @Test
        void allModesExist() {
            assertEquals(3, PlaybackMode.values().length);
            assertNotNull(PlaybackMode.LOOP);
            assertNotNull(PlaybackMode.SHUFFLE);
            assertNotNull(PlaybackMode.ONCE);
        }
    }
}
