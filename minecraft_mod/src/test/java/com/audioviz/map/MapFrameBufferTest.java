package com.audioviz.map;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class MapFrameBufferTest {

    @Test
    void testSetPixelTracksDirtyRect() {
        MapFrameBuffer buf = new MapFrameBuffer();
        buf.setPixel(10, 20, (byte) 42);
        var dirty = buf.getDirtyRect();
        assertTrue(dirty.isPresent());
        assertEquals(10, dirty.get().x());
        assertEquals(20, dirty.get().z());
        assertEquals(1, dirty.get().width());
        assertEquals(1, dirty.get().height());
    }

    @Test
    void testMultiplePixelsExpandDirtyRect() {
        MapFrameBuffer buf = new MapFrameBuffer();
        buf.setPixel(10, 20, (byte) 1);
        buf.setPixel(50, 60, (byte) 2);
        var dirty = buf.getDirtyRect();
        assertTrue(dirty.isPresent());
        assertEquals(10, dirty.get().x());
        assertEquals(20, dirty.get().z());
        assertEquals(41, dirty.get().width());  // 50 - 10 + 1
        assertEquals(41, dirty.get().height()); // 60 - 20 + 1
    }

    @Test
    void testClearDirtyResetsTracking() {
        MapFrameBuffer buf = new MapFrameBuffer();
        buf.setPixel(5, 5, (byte) 1);
        buf.clearDirty();
        assertTrue(buf.getDirtyRect().isEmpty());
    }

    @Test
    void testNoDirtyWhenNoChanges() {
        MapFrameBuffer buf = new MapFrameBuffer();
        assertTrue(buf.getDirtyRect().isEmpty());
    }

    @Test
    void testSameValueDoesNotDirty() {
        MapFrameBuffer buf = new MapFrameBuffer();
        // Default is 0, setting to 0 should not dirty
        buf.setPixel(5, 5, (byte) 0);
        assertTrue(buf.getDirtyRect().isEmpty());
    }

    @Test
    void testBulkWriteFromBitmapFrame() {
        int[] argbPixels = new int[32 * 18];
        Arrays.fill(argbPixels, 0xFFFF0000); // solid red
        MapFrameBuffer buf = new MapFrameBuffer();
        buf.writeFromArgb(argbPixels, 32, 18, 0, 0);
        // All pixels in 32x18 region should be the map color for red
        byte expectedRed = MapPalette.argbToMapColor(0xFFFF0000);
        assertEquals(expectedRed, buf.getPixel(0, 0));
        assertEquals(expectedRed, buf.getPixel(31, 17));
        assertEquals(0, buf.getPixel(32, 0)); // outside written region
    }

    @Test
    void testExtractDirtyData() {
        MapFrameBuffer buf = new MapFrameBuffer();
        buf.setPixel(0, 0, (byte) 10);
        buf.setPixel(1, 0, (byte) 20);
        buf.setPixel(0, 1, (byte) 30);
        buf.setPixel(1, 1, (byte) 40);

        // Column-major: data[x * height + z] as required by MapState.UpdateData
        byte[] data = buf.extractDirtyData();
        assertEquals(4, data.length); // 2x2
        assertEquals(10, data[0]); // x=0, z=0
        assertEquals(30, data[1]); // x=0, z=1
        assertEquals(20, data[2]); // x=1, z=0
        assertEquals(40, data[3]); // x=1, z=1
    }

    @Test
    void testOutOfBoundsIgnored() {
        MapFrameBuffer buf = new MapFrameBuffer();
        buf.setPixel(-1, 0, (byte) 1);
        buf.setPixel(128, 0, (byte) 1);
        buf.setPixel(0, -1, (byte) 1);
        buf.setPixel(0, 128, (byte) 1);
        assertTrue(buf.getDirtyRect().isEmpty());
    }
}
