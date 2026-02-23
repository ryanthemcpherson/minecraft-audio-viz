package com.audioviz.bitmap.adaptive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
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
}
