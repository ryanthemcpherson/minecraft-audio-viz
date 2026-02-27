package com.audioviz.map;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class MapDisplayManagerTest {

    @Test
    void testSingleTileDisplay() {
        // 32x18 display fits in a single 128x128 map
        MapDisplayManager mgr = new MapDisplayManager(32, 18);
        assertEquals(1, mgr.getTileCountX());
        assertEquals(1, mgr.getTileCountZ());
        assertEquals(1, mgr.getTotalTiles());
    }

    @Test
    void testLargerDisplayTiles() {
        // 200x100 needs 2x1 tiles (each tile is 128x128)
        MapDisplayManager mgr = new MapDisplayManager(200, 100);
        assertEquals(2, mgr.getTileCountX());
        assertEquals(1, mgr.getTileCountZ());
        assertEquals(2, mgr.getTotalTiles());
    }

    @Test
    void testExactBoundaryTiles() {
        // 128x128 = exactly 1 tile
        MapDisplayManager mgr = new MapDisplayManager(128, 128);
        assertEquals(1, mgr.getTileCountX());
        assertEquals(1, mgr.getTileCountZ());
    }

    @Test
    void testOverBoundaryTiles() {
        // 129x129 = 2x2 tiles
        MapDisplayManager mgr = new MapDisplayManager(129, 129);
        assertEquals(2, mgr.getTileCountX());
        assertEquals(2, mgr.getTileCountZ());
        assertEquals(4, mgr.getTotalTiles());
    }

    @Test
    void testWriteFrameToSingleTile() {
        MapDisplayManager mgr = new MapDisplayManager(32, 18);
        int[] argb = new int[32 * 18];
        Arrays.fill(argb, 0xFFFF0000);
        mgr.writeFrame(argb, 32, 18);
        assertTrue(mgr.getTile(0, 0).getDirtyRect().isPresent());
    }

    @Test
    void testWriteFrameSpansMultipleTiles() {
        MapDisplayManager mgr = new MapDisplayManager(200, 100);
        int[] argb = new int[200 * 100];
        Arrays.fill(argb, 0xFF00FF00); // green
        mgr.writeFrame(argb, 200, 100);
        // Both tiles should be dirty
        assertTrue(mgr.getTile(0, 0).getDirtyRect().isPresent());
        assertTrue(mgr.getTile(1, 0).getDirtyRect().isPresent());
    }
}
