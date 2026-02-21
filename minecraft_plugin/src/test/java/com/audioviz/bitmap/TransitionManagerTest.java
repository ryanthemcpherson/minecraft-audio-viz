package com.audioviz.bitmap;

import com.audioviz.bitmap.transitions.*;
import com.audioviz.patterns.AudioState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TransitionManager} and transitions.
 * Uses lightweight stub patterns to test the transition pipeline.
 */
class TransitionManagerTest {

    private TransitionManager tm;

    /** Stub pattern that fills with a solid color. */
    static class SolidPattern extends BitmapPattern {
        final int color;
        SolidPattern(String id, int color) {
            super(id, id, "Solid " + id);
            this.color = color;
        }
        @Override
        public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
            buffer.fill(color);
        }
    }

    private final SolidPattern redPattern = new SolidPattern("red", 0xFFFF0000);
    private final SolidPattern bluePattern = new SolidPattern("blue", 0xFF0000FF);

    @BeforeEach
    void setUp() {
        tm = new TransitionManager();
    }

    @Nested
    @DisplayName("Built-in Transitions Registry")
    class Registry {
        @Test
        void hasEightBuiltInTransitions() {
            List<String> ids = tm.getTransitionIds();
            assertEquals(8, ids.size(), "Should have 8 built-in transitions");
        }

        @Test
        void containsCrossfade() {
            assertNotNull(tm.getTransition("crossfade"));
        }

        @Test
        void containsDissolve() {
            assertNotNull(tm.getTransition("dissolve"));
        }

        @Test
        void containsWipeDirections() {
            assertNotNull(tm.getTransition("wipe_left"));
            assertNotNull(tm.getTransition("wipe_right"));
            assertNotNull(tm.getTransition("wipe_up"));
            assertNotNull(tm.getTransition("wipe_down"));
        }

        @Test
        void containsIris() {
            assertNotNull(tm.getTransition("iris_open"));
            assertNotNull(tm.getTransition("iris_close"));
        }

        @Test
        void allHaveNames() {
            for (String id : tm.getTransitionIds()) {
                BitmapTransition t = tm.getTransition(id);
                assertNotNull(t.getName(), "Transition " + id + " should have a name");
                assertFalse(t.getName().isEmpty());
            }
        }

        @Test
        void unknownReturnsNull() {
            assertNull(tm.getTransition("nonexistent"));
        }
    }

    @Nested
    @DisplayName("Transition Lifecycle")
    class Lifecycle {
        @Test
        void notTransitioningByDefault() {
            assertFalse(tm.isTransitioning("zone1"));
        }

        @Test
        void startTransitionMarksActive() {
            tm.startTransition("zone1", redPattern, bluePattern,
                "crossfade", 20, 8, 8);
            assertTrue(tm.isTransitioning("zone1"));
        }

        @Test
        void zeroDurationDoesNotStartTransition() {
            tm.startTransition("zone1", redPattern, bluePattern,
                "crossfade", 0, 8, 8);
            assertFalse(tm.isTransitioning("zone1"));
        }

        @Test
        void negativeDurationDoesNotStartTransition() {
            tm.startTransition("zone1", redPattern, bluePattern,
                "crossfade", -1, 8, 8);
            assertFalse(tm.isTransitioning("zone1"));
        }

        @Test
        void tickProgressesToCompletion() {
            tm.startTransition("zone1", redPattern, bluePattern,
                "crossfade", 5, 4, 4);

            BitmapFrameBuffer output = new BitmapFrameBuffer(4, 4);
            AudioState audio = AudioState.forTest(0, false);

            // Tick 5 times (the duration)
            boolean active = true;
            for (int i = 0; i < 5; i++) {
                active = tm.tick("zone1", output, audio, i * 0.05);
            }

            assertFalse(active, "Transition should be complete after duration ticks");
            assertFalse(tm.isTransitioning("zone1"));
        }

        @Test
        void tickReturnsBufferContent() {
            tm.startTransition("zone1", redPattern, bluePattern,
                "crossfade", 10, 4, 4);

            BitmapFrameBuffer output = new BitmapFrameBuffer(4, 4);
            AudioState audio = AudioState.forTest(0, false);

            tm.tick("zone1", output, audio, 0);

            // After one tick of crossfade, should have some non-zero content
            int pixel = output.getPixel(0, 0);
            assertTrue(pixel != 0, "Output should have content from transition");
        }

        @Test
        void completedTransitionShowsTargetPattern() {
            tm.startTransition("zone1", redPattern, bluePattern,
                "crossfade", 2, 4, 4);

            BitmapFrameBuffer output = new BitmapFrameBuffer(4, 4);
            AudioState audio = AudioState.forTest(0, false);

            // Run past completion
            tm.tick("zone1", output, audio, 0);
            tm.tick("zone1", output, audio, 0.05);

            // Last frame should be dominated by blue
            int pixel = output.getPixel(0, 0);
            int b = pixel & 0xFF;
            assertTrue(b > 200, "Blue channel should dominate at end of transition, got " + b);
        }
    }

    @Nested
    @DisplayName("Cancel")
    class Cancel {
        @Test
        void cancelStopsTransition() {
            tm.startTransition("zone1", redPattern, bluePattern,
                "crossfade", 100, 4, 4);
            assertTrue(tm.isTransitioning("zone1"));

            tm.cancel("zone1");
            assertFalse(tm.isTransitioning("zone1"));
        }

        @Test
        void cancelNonexistentZoneNoOp() {
            assertDoesNotThrow(() -> tm.cancel("nonexistent"));
        }

        @Test
        void cancelAllClearsEverything() {
            tm.startTransition("zone1", redPattern, bluePattern, "crossfade", 100, 4, 4);
            tm.startTransition("zone2", redPattern, bluePattern, "dissolve", 100, 4, 4);

            tm.cancelAll();

            assertFalse(tm.isTransitioning("zone1"));
            assertFalse(tm.isTransitioning("zone2"));
        }
    }

    @Nested
    @DisplayName("Multiple Zones")
    class MultipleZones {
        @Test
        void independentTransitions() {
            tm.startTransition("zone1", redPattern, bluePattern, "crossfade", 10, 4, 4);
            tm.startTransition("zone2", bluePattern, redPattern, "dissolve", 20, 4, 4);

            assertTrue(tm.isTransitioning("zone1"));
            assertTrue(tm.isTransitioning("zone2"));

            // Complete zone1
            BitmapFrameBuffer out = new BitmapFrameBuffer(4, 4);
            AudioState audio = AudioState.forTest(0, false);
            for (int i = 0; i < 10; i++) {
                tm.tick("zone1", out, audio, i * 0.05);
            }

            assertFalse(tm.isTransitioning("zone1"));
            assertTrue(tm.isTransitioning("zone2"), "zone2 should still be transitioning");
        }
    }

    @Nested
    @DisplayName("Incoming Pattern Access")
    class IncomingPattern {
        @Test
        void getIncomingPatternDuringTransition() {
            tm.startTransition("zone1", redPattern, bluePattern,
                "crossfade", 10, 4, 4);

            BitmapPattern incoming = tm.getIncomingPattern("zone1");
            assertNotNull(incoming);
            assertEquals("blue", incoming.getId());
        }

        @Test
        void getIncomingPatternWhenNotTransitioning() {
            assertNull(tm.getIncomingPattern("zone1"));
        }
    }

    @Nested
    @DisplayName("Individual Transitions")
    class IndividualTransitions {
        @Test
        void crossfadeProducesGradualBlend() {
            CrossfadeTransition xfade = new CrossfadeTransition();
            BitmapFrameBuffer from = new BitmapFrameBuffer(4, 4);
            BitmapFrameBuffer to = new BitmapFrameBuffer(4, 4);
            BitmapFrameBuffer out = new BitmapFrameBuffer(4, 4);

            from.fill(0xFFFF0000);
            to.fill(0xFF0000FF);

            // At t=0.5, should be a blend
            xfade.blend(from, to, out, 0.5);
            int pixel = out.getPixel(0, 0);
            int r = (pixel >> 16) & 0xFF;
            int b = pixel & 0xFF;
            assertTrue(r > 50 && r < 200, "R should be partially present: " + r);
            assertTrue(b > 50 && b < 200, "B should be partially present: " + b);
        }

        @Test
        void dissolveConvergesFullyAtOne() {
            DissolveTransition dissolve = new DissolveTransition();
            BitmapFrameBuffer from = new BitmapFrameBuffer(8, 8);
            BitmapFrameBuffer to = new BitmapFrameBuffer(8, 8);
            BitmapFrameBuffer out = new BitmapFrameBuffer(8, 8);

            from.fill(0xFFFF0000);
            to.fill(0xFF0000FF);

            dissolve.blend(from, to, out, 1.0);
            // At t=1.0, every pixel should be blue
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    assertEquals(0xFF0000FF, out.getPixel(x, y),
                        "All pixels should be 'to' at t=1.0");
                }
            }
        }

        @Test
        void wipeProgressesFromEdge() {
            WipeTransition wipe = new WipeTransition(WipeTransition.Direction.LEFT, 0.08);
            BitmapFrameBuffer from = new BitmapFrameBuffer(8, 4);
            BitmapFrameBuffer to = new BitmapFrameBuffer(8, 4);
            BitmapFrameBuffer out = new BitmapFrameBuffer(8, 4);

            from.fill(0xFFFF0000);
            to.fill(0xFF0000FF);

            // At t=0.5, left half should be mostly blue, right half mostly red
            wipe.blend(from, to, out, 0.5);
            int leftPixel = out.getPixel(0, 0);
            int rightPixel = out.getPixel(7, 0);

            int leftBlue = leftPixel & 0xFF;
            int rightRed = (rightPixel >> 16) & 0xFF;

            assertTrue(leftBlue > 128, "Left pixel should be mostly blue in LEFT wipe: " + leftBlue);
            assertTrue(rightRed > 128, "Right pixel should be mostly red in LEFT wipe: " + rightRed);
        }
    }
}
