package com.audioviz.sequence;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SequencePlayerTest {

    private Sequence makeSequence(int stepCount, int durationPerStep) {
        Sequence seq = new Sequence("test");
        seq.setDefaultStepDuration(durationPerStep);
        for (int i = 0; i < stepCount; i++) {
            seq.addStep(new SequenceStep(
                Map.of("zone1", "pattern_" + i), 0, null, 0));
        }
        return seq;
    }

    @Nested
    @DisplayName("Step Advancement")
    class StepAdvancement {

        @Test
        @DisplayName("starts at step 0")
        void startsAtZero() {
            var player = new SequencePlayer(makeSequence(3, 100));
            assertEquals(0, player.getCurrentStepIndex());
        }

        @Test
        @DisplayName("advances after duration expires")
        void advancesOnExpiry() {
            var player = new SequencePlayer(makeSequence(3, 10));
            List<SequencePlayer.StepTransition> transitions = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                var t = player.tick();
                if (t != null) transitions.add(t);
            }
            assertEquals(1, player.getCurrentStepIndex());
            assertEquals(1, transitions.size());
        }

        @Test
        @DisplayName("reports pattern changes in transition")
        void reportsPatterns() {
            var player = new SequencePlayer(makeSequence(3, 5));
            SequencePlayer.StepTransition t = null;
            for (int i = 0; i < 5; i++) {
                var result = player.tick();
                if (result != null) t = result;
            }
            assertNotNull(t);
            assertEquals("pattern_1", t.zonePatterns().get("zone1"));
        }
    }

    @Nested
    @DisplayName("Loop Mode")
    class LoopMode {

        @Test
        @DisplayName("loops back to step 0 after last step")
        void loopsToStart() {
            var seq = makeSequence(2, 5);
            seq.setMode(PlaybackMode.LOOP);
            var player = new SequencePlayer(seq);

            for (int i = 0; i < 10; i++) player.tick();
            assertEquals(0, player.getCurrentStepIndex());
            assertFalse(player.isFinished());
        }
    }

    @Nested
    @DisplayName("Once Mode")
    class OnceMode {

        @Test
        @DisplayName("stops after last step")
        void stopsAtEnd() {
            var seq = makeSequence(2, 5);
            seq.setMode(PlaybackMode.ONCE);
            var player = new SequencePlayer(seq);

            for (int i = 0; i < 10; i++) player.tick();
            assertTrue(player.isFinished());
        }
    }

    @Nested
    @DisplayName("Shuffle Mode")
    class ShuffleMode {

        @Test
        @DisplayName("visits all steps before repeating")
        void visitsAll() {
            var seq = makeSequence(4, 5);
            seq.setMode(PlaybackMode.SHUFFLE);
            var player = new SequencePlayer(seq);

            Set<Integer> visited = new HashSet<>();
            visited.add(player.getCurrentStepIndex());
            for (int i = 0; i < 20; i++) {
                player.tick();
                visited.add(player.getCurrentStepIndex());
            }
            assertEquals(4, visited.size());
        }
    }

    @Nested
    @DisplayName("Skip")
    class Skip {

        @Test
        @DisplayName("skip advances immediately")
        void skipAdvances() {
            var player = new SequencePlayer(makeSequence(3, 100));
            assertEquals(0, player.getCurrentStepIndex());
            var t = player.skip();
            assertNotNull(t);
            assertEquals(1, player.getCurrentStepIndex());
        }
    }

    @Nested
    @DisplayName("Empty Sequence")
    class EmptySequence {

        @Test
        @DisplayName("empty sequence is immediately finished")
        void emptyFinishes() {
            var player = new SequencePlayer(new Sequence("empty"));
            assertTrue(player.isFinished());
        }
    }
}
