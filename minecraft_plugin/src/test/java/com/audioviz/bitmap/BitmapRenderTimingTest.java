package com.audioviz.bitmap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BitmapRenderTimingTest {

    @Test
    void renderTimerTracksNanos() {
        BitmapRenderTimer timer = new BitmapRenderTimer();

        timer.recordRender(5_000_000L);  // 5ms
        timer.recordRender(10_000_000L); // 10ms
        timer.recordRender(3_000_000L);  // 3ms

        assertEquals(3, timer.getFrameCount());
        assertTrue(timer.getMaxRenderNanos() >= 10_000_000L);
        assertTrue(timer.getAvgRenderNanos() >= 5_000_000L);
    }

    @Test
    void renderTimerResetsStats() {
        BitmapRenderTimer timer = new BitmapRenderTimer();
        timer.recordRender(10_000_000L);

        BitmapRenderTimer.Stats stats = timer.snapshotAndReset();

        assertEquals(1, stats.frameCount());
        assertEquals(0, timer.getFrameCount());
    }

    @Test
    void renderTimerTracksSkippedFrames() {
        BitmapRenderTimer timer = new BitmapRenderTimer();
        timer.recordSkip();
        timer.recordSkip();
        timer.recordRender(1_000_000L);

        assertEquals(1, timer.getFrameCount());
        assertEquals(2, timer.getSkipCount());
    }
}
