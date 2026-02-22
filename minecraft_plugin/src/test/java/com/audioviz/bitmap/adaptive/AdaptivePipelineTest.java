package com.audioviz.bitmap.adaptive;

import com.audioviz.bitmap.BitmapFrameBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Integration tests for the full adaptive pipeline:
 * frame buffer → cell grid → merge → assign → diff.
 */
class AdaptivePipelineTest {

    private static final int BLACK = 0xFF000000;
    private static final int RED   = 0xFFFF0000;
    private static final int CYAN  = 0xFF00CCFF;

    @Test
    @DisplayName("64x64 solid color: merges to 1 rect, assigner produces 1 update")
    void solidImage() {
        BitmapFrameBuffer buf = new BitmapFrameBuffer(64, 64);
        buf.fill(0xFF112233);

        List<MergedRect> rects = CellGridMerger.merge(buf.getRawPixels(), 64, 64);

        assertEquals(1, rects.size());
        assertEquals(64, rects.get(0).w());
        assertEquals(32, rects.get(0).h()); // 64 pixel rows → 32 cell rows
        assertTrue(rects.get(0).isUniform());

        // Full pipeline: assign to entity slots
        var assigner = new AdaptiveEntityAssigner(500);
        var diff = assigner.assign(rects, 1.0f);
        assertEquals(1, diff.geometryUpdates().size());
        assertEquals(1, diff.backgroundUpdates().size());
    }

    @Test
    @DisplayName("64x64 with center square: significant merging, full pipeline")
    void imageWithCenterSquare() {
        BitmapFrameBuffer buf = new BitmapFrameBuffer(64, 64);
        buf.fill(BLACK);
        buf.fillRect(24, 24, 16, 16, RED);

        List<MergedRect> rects = CellGridMerger.merge(buf.getRawPixels(), 64, 64);

        // Should be far fewer than 2048 (64*32) rects
        assertTrue(rects.size() < 100, "Expected significant merging, got " + rects.size());

        // Verify total coverage
        int totalCells = rects.stream().mapToInt(MergedRect::cellCount).sum();
        assertEquals(64 * 32, totalCells);

        // Full pipeline: all rects fit within budget, first frame all dirty
        var assigner = new AdaptiveEntityAssigner(500);
        var diff = assigner.assign(rects, 1.0f);
        assertEquals(rects.size(), diff.geometryUpdates().size());
        assertFalse(diff.poolExhausted());
    }

    @Test
    @DisplayName("assigner reports zero dirty on identical frames")
    void staticImageZeroDirty() {
        BitmapFrameBuffer buf = new BitmapFrameBuffer(32, 32);
        buf.fill(BLACK);
        buf.fillRect(8, 8, 16, 16, RED);

        var assigner = new AdaptiveEntityAssigner(500);

        List<MergedRect> rects = CellGridMerger.merge(buf.getRawPixels(), 32, 32);
        assigner.assign(rects, 1.0f); // first frame

        // Second identical frame
        var diff = assigner.assign(rects, 1.0f);

        assertEquals(0, diff.geometryUpdates().size());
        assertEquals(0, diff.backgroundUpdates().size());
        assertEquals(0, diff.textUpdates().size());
    }

    @Test
    @DisplayName("spectrum bars pattern: entity count within budget")
    void spectrumBarsEntityCount() {
        BitmapFrameBuffer buf = new BitmapFrameBuffer(64, 32);
        buf.fill(BLACK);

        // Simulate 5 spectrum bars
        for (int band = 0; band < 5; band++) {
            int barX = band * 12 + 2;
            int barW = 10;
            double height = 0.3 + band * 0.15;
            int fillRows = (int) (height * 32);
            buf.fillRect(barX, 32 - fillRows, barW, fillRows, CYAN);
        }

        List<MergedRect> rects = CellGridMerger.merge(buf.getRawPixels(), 64, 32);

        // Black background should merge heavily; bars are solid blocks
        assertTrue(rects.size() < 200, "Expected <200 rects for simple bars, got " + rects.size());

        // Full pipeline: assign and verify all fit
        var assigner = new AdaptiveEntityAssigner(500);
        var diff = assigner.assign(rects, 1.0f);
        assertEquals(rects.size(), diff.geometryUpdates().size());
        assertFalse(diff.poolExhausted());
    }

    @Test
    @DisplayName("odd pixel height handled correctly")
    void oddPixelHeight() {
        BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 3);
        buf.fill(RED);

        List<MergedRect> rects = CellGridMerger.merge(buf.getRawPixels(), 4, 3);

        // 4x3 pixels → 4x2 cell grid (last row: top=red, bottom=0)
        // Top cell row: all (red,red) → merges to 1 rect
        // Bottom cell row: all (red,0) → merges to 1 rect (different from top)
        assertEquals(2, rects.size());
        int totalCells = rects.stream().mapToInt(MergedRect::cellCount).sum();
        assertEquals(8, totalCells); // 4x2
    }
}
