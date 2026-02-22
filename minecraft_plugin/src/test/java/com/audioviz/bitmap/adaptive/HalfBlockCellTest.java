package com.audioviz.bitmap.adaptive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

class HalfBlockCellTest {

    @Test
    @DisplayName("cells with same top and bottom are uniform")
    void uniformCell() {
        var cell = new HalfBlockCell(0xFF000000, 0xFF000000);
        assertTrue(cell.isUniform());
    }

    @Test
    @DisplayName("cells with different top and bottom are not uniform")
    void nonUniformCell() {
        var cell = new HalfBlockCell(0xFFFF0000, 0xFF00FF00);
        assertFalse(cell.isUniform());
    }

    @Test
    @DisplayName("equality based on both colors")
    void equality() {
        var a = new HalfBlockCell(0xFFFF0000, 0xFF00FF00);
        var b = new HalfBlockCell(0xFFFF0000, 0xFF00FF00);
        var c = new HalfBlockCell(0xFFFF0000, 0xFF0000FF);
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    @DisplayName("MergedRect stores position, size, and colors")
    void mergedRectBasics() {
        var rect = new MergedRect(2, 3, 4, 5, 0xFFFF0000, 0xFF00FF00);
        assertEquals(2, rect.x());
        assertEquals(3, rect.y());
        assertEquals(4, rect.w());
        assertEquals(5, rect.h());
        assertEquals(20, rect.cellCount());
    }
}
