package com.audioviz.bitmap.adaptive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

class AdaptiveEntityAssignerTest {

    private static final int BLACK = 0xFF000000;
    private static final int RED   = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;

    @Test
    @DisplayName("first frame: all slots are dirty")
    void firstFrameAllDirty() {
        var assigner = new AdaptiveEntityAssigner(100);
        List<MergedRect> rects = List.of(
            new MergedRect(0, 0, 2, 2, BLACK, BLACK),
            new MergedRect(2, 0, 1, 1, RED, GREEN)
        );

        var diff = assigner.assign(rects, 1.0f);

        assertEquals(2, diff.geometryUpdates().size());
        assertEquals(2, diff.backgroundUpdates().size());
        // Both slots need text updates on first frame:
        // uniform (BLACK,BLACK) → sentinel -1 (space), non-uniform (RED,GREEN) → GREEN
        assertEquals(2, diff.textUpdates().size());
    }

    @Test
    @DisplayName("identical second frame: zero dirty")
    void identicalFrameZeroDirty() {
        var assigner = new AdaptiveEntityAssigner(100);
        List<MergedRect> rects = List.of(
            new MergedRect(0, 0, 4, 2, BLACK, BLACK)
        );

        assigner.assign(rects, 1.0f);
        var diff = assigner.assign(rects, 1.0f);

        assertEquals(0, diff.geometryUpdates().size());
        assertEquals(0, diff.backgroundUpdates().size());
        assertEquals(0, diff.textUpdates().size());
        assertEquals(0, diff.hideCount());
    }

    @Test
    @DisplayName("fewer rects than before: excess slots get hidden")
    void excessSlotsHidden() {
        var assigner = new AdaptiveEntityAssigner(100);

        assigner.assign(List.of(
            new MergedRect(0, 0, 1, 1, BLACK, BLACK),
            new MergedRect(1, 0, 1, 1, RED, RED),
            new MergedRect(2, 0, 1, 1, GREEN, GREEN)
        ), 1.0f);

        var diff = assigner.assign(List.of(
            new MergedRect(0, 0, 3, 1, BLACK, BLACK)
        ), 1.0f);

        assertEquals(2, diff.hideCount());
    }

    @Test
    @DisplayName("pool exhaustion: rects beyond capacity are dropped")
    void poolExhaustion() {
        var assigner = new AdaptiveEntityAssigner(2);

        List<MergedRect> rects = List.of(
            new MergedRect(0, 0, 1, 1, BLACK, BLACK),
            new MergedRect(1, 0, 1, 1, RED, RED),
            new MergedRect(2, 0, 1, 1, GREEN, GREEN)
        );

        var diff = assigner.assign(rects, 1.0f);

        assertEquals(2, diff.geometryUpdates().size());
        assertTrue(diff.poolExhausted());
    }

    @Test
    @DisplayName("only bg changes when uniform color changes")
    void bgOnlyChange() {
        var assigner = new AdaptiveEntityAssigner(100);

        assigner.assign(List.of(new MergedRect(0, 0, 2, 2, BLACK, BLACK)), 1.0f);
        var diff = assigner.assign(List.of(new MergedRect(0, 0, 2, 2, RED, RED)), 1.0f);

        assertEquals(0, diff.geometryUpdates().size());
        assertEquals(1, diff.backgroundUpdates().size());
        assertEquals(0, diff.textUpdates().size());
    }

    @Test
    @DisplayName("uniform to non-uniform transition triggers text update")
    void uniformToNonUniform() {
        var assigner = new AdaptiveEntityAssigner(100);

        assigner.assign(List.of(new MergedRect(0, 0, 1, 1, RED, RED)), 1.0f); // uniform
        var diff = assigner.assign(List.of(new MergedRect(0, 0, 1, 1, RED, GREEN)), 1.0f); // non-uniform

        // Should have text update (need to switch from space to ▄ with GREEN)
        assertTrue(diff.textUpdates().size() > 0);
        assertEquals(GREEN, diff.textUpdates().get(0).argb());
    }

    @Nested
    @DisplayName("Text Update Semantics")
    class TextUpdates {
        @Test
        void uniformCellGetsSentinelMinusOne() {
            var assigner = new AdaptiveEntityAssigner(4);
            List<MergedRect> rects = List.of(
                new MergedRect(0, 0, 1, 1, 0xFFFF0000, 0xFFFF0000) // uniform
            );
            var diff = assigner.assign(rects, 1.0f);
            // Uniform cells should get text update with argb = -1 (space sentinel)
            assertFalse(diff.textUpdates().isEmpty(), "First frame should have text update");
            assertEquals(-1, diff.textUpdates().get(0).argb(), "Uniform cell should use -1 sentinel");
        }

        @Test
        void nonUniformCellGetsBottomColor() {
            var assigner = new AdaptiveEntityAssigner(4);
            List<MergedRect> rects = List.of(
                new MergedRect(0, 0, 1, 1, 0xFFFF0000, 0xFF00FF00) // non-uniform
            );
            var diff = assigner.assign(rects, 1.0f);
            assertFalse(diff.textUpdates().isEmpty());
            assertEquals(0xFF00FF00, diff.textUpdates().get(0).argb());
        }

        @Test
        void uniformToNonUniformTriggersTextUpdate() {
            var assigner = new AdaptiveEntityAssigner(4);
            // Frame 1: uniform
            assigner.assign(List.of(
                new MergedRect(0, 0, 1, 1, 0xFFAA0000, 0xFFAA0000)
            ), 1.0f);
            // Frame 2: non-uniform (same position)
            var diff = assigner.assign(List.of(
                new MergedRect(0, 0, 1, 1, 0xFFAA0000, 0xFF00BB00)
            ), 1.0f);
            assertFalse(diff.textUpdates().isEmpty(), "Uniform→non-uniform should trigger text update");
            assertEquals(0xFF00BB00, diff.textUpdates().get(0).argb());
        }

        @Test
        void nonUniformToUniformTriggersTextUpdate() {
            var assigner = new AdaptiveEntityAssigner(4);
            // Frame 1: non-uniform
            assigner.assign(List.of(
                new MergedRect(0, 0, 1, 1, 0xFFAA0000, 0xFF00BB00)
            ), 1.0f);
            // Frame 2: uniform
            var diff = assigner.assign(List.of(
                new MergedRect(0, 0, 1, 1, 0xFFCC0000, 0xFFCC0000)
            ), 1.0f);
            assertFalse(diff.textUpdates().isEmpty(), "Non-uniform→uniform should trigger text update");
            assertEquals(-1, diff.textUpdates().get(0).argb(), "Should switch to space (-1)");
        }
    }

    @Nested
    @DisplayName("Geometry Dirty Detection")
    class GeometryDirty {
        @Test
        void positionChangeIsDirty() {
            var assigner = new AdaptiveEntityAssigner(4);
            assigner.assign(List.of(new MergedRect(0, 0, 1, 1, 0xFF000000, 0xFF000000)), 1.0f);
            var diff = assigner.assign(List.of(new MergedRect(1, 0, 1, 1, 0xFF000000, 0xFF000000)), 1.0f);
            assertFalse(diff.geometryUpdates().isEmpty(), "Position change should be dirty");
        }

        @Test
        void sizeChangeIsDirty() {
            var assigner = new AdaptiveEntityAssigner(4);
            assigner.assign(List.of(new MergedRect(0, 0, 1, 1, 0xFF000000, 0xFF000000)), 1.0f);
            var diff = assigner.assign(List.of(new MergedRect(0, 0, 2, 1, 0xFF000000, 0xFF000000)), 1.0f);
            assertFalse(diff.geometryUpdates().isEmpty(), "Size change should be dirty");
        }

        @Test
        void colorOnlyChangeNotGeoDirty() {
            var assigner = new AdaptiveEntityAssigner(4);
            assigner.assign(List.of(new MergedRect(0, 0, 1, 1, 0xFF000000, 0xFF000000)), 1.0f);
            var diff = assigner.assign(List.of(new MergedRect(0, 0, 1, 1, 0xFFFF0000, 0xFFFF0000)), 1.0f);
            assertTrue(diff.geometryUpdates().isEmpty(), "Color-only change should NOT be geo dirty");
            assertFalse(diff.backgroundUpdates().isEmpty(), "Color-only change should be bg dirty");
        }
    }

    @Nested
    @DisplayName("Hide Mechanics")
    class HideMechanics {
        @Test
        void reducingRectsHidesPrevious() {
            var assigner = new AdaptiveEntityAssigner(10);
            assigner.assign(List.of(
                new MergedRect(0, 0, 1, 1, 0xFFAA0000, 0xFFAA0000),
                new MergedRect(1, 0, 1, 1, 0xFFBB0000, 0xFFBB0000),
                new MergedRect(2, 0, 1, 1, 0xFFCC0000, 0xFFCC0000)
            ), 1.0f);
            var diff = assigner.assign(List.of(
                new MergedRect(0, 0, 1, 1, 0xFFAA0000, 0xFFAA0000)
            ), 1.0f);
            assertEquals(1, diff.hideStart());
            assertEquals(2, diff.hideCount(), "Should hide 2 previously active slots");
        }

        @Test
        void increasingRectsNoHide() {
            var assigner = new AdaptiveEntityAssigner(10);
            assigner.assign(List.of(
                new MergedRect(0, 0, 1, 1, 0xFFAA0000, 0xFFAA0000)
            ), 1.0f);
            var diff = assigner.assign(List.of(
                new MergedRect(0, 0, 1, 1, 0xFFAA0000, 0xFFAA0000),
                new MergedRect(1, 0, 1, 1, 0xFFBB0000, 0xFFBB0000)
            ), 1.0f);
            assertEquals(0, diff.hideCount());
        }
    }

    @Nested
    @DisplayName("Pixel Scale")
    class PixelScale {
        @Test
        void scaleAffectsGeometry() {
            var assigner = new AdaptiveEntityAssigner(4);
            var diff = assigner.assign(List.of(
                new MergedRect(2, 3, 1, 1, 0xFF000000, 0xFF000000)
            ), 0.5f);
            var geo = diff.geometryUpdates().get(0);
            assertEquals(1.0f, geo.x(), 0.001, "x = 2 * 0.5");
            assertEquals(1.5f, geo.y(), 0.001, "y = 3 * 0.5");
            assertEquals(0.5f, geo.scaleX(), 0.001);
            assertEquals(0.5f, geo.scaleY(), 0.001);
        }
    }
}
