package com.audioviz.bitmap;

import com.audioviz.patterns.AudioState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AsyncBitmapRendererTest {

    private AsyncBitmapRenderer renderer;

    @AfterEach
    void tearDown() {
        if (renderer != null) renderer.shutdown();
    }

    static class SolidPattern extends BitmapPattern {
        final int color;
        SolidPattern(int color) {
            super("solid", "Solid", "test");
            this.color = color;
        }
        @Override
        public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
            buffer.fill(color);
        }
    }

    static class BlockingPattern extends BitmapPattern {
        final CountDownLatch renderStarted = new CountDownLatch(1);
        final CountDownLatch allowFinish = new CountDownLatch(1);

        BlockingPattern() { super("blocking", "Blocking", "test"); }

        @Override
        public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
            renderStarted.countDown();
            try { allowFinish.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            buffer.fill(0xFF00FF00);
        }
    }

    @Test
    void submitAndConsumeFrame() throws Exception {
        renderer = new AsyncBitmapRenderer();
        renderer.registerZone("test", 8, 8);
        AudioState audio = AudioState.silent();

        renderer.submitRender("test", new SolidPattern(0xFFFF0000), audio, 1.0);
        Thread.sleep(200);

        int[] pixels = renderer.consumeCompletedFrame("test");
        assertNotNull(pixels, "Should have a completed frame");
        assertEquals(64, pixels.length);
        assertEquals(0xFFFF0000, pixels[0]);
    }

    @Test
    void consumeReturnsNullWhenNoFrame() {
        renderer = new AsyncBitmapRenderer();
        renderer.registerZone("test", 4, 4);
        assertNull(renderer.consumeCompletedFrame("test"));
    }

    @Test
    void skipsSubmitWhenRenderInFlight() throws Exception {
        renderer = new AsyncBitmapRenderer();
        renderer.registerZone("test", 4, 4);
        BlockingPattern blocking = new BlockingPattern();
        AudioState audio = AudioState.silent();

        assertTrue(renderer.submitRender("test", blocking, audio, 1.0));
        assertTrue(blocking.renderStarted.await(2, TimeUnit.SECONDS));

        assertFalse(renderer.submitRender("test", new SolidPattern(0xFF0000FF), audio, 2.0));

        blocking.allowFinish.countDown();
    }

    @Test
    void consumeClearsCompletedFrame() throws Exception {
        renderer = new AsyncBitmapRenderer();
        renderer.registerZone("test", 4, 4);
        AudioState audio = AudioState.silent();

        renderer.submitRender("test", new SolidPattern(0xFFFF0000), audio, 1.0);
        Thread.sleep(200);

        assertNotNull(renderer.consumeCompletedFrame("test"), "First consume should return frame");
        assertNull(renderer.consumeCompletedFrame("test"), "Second consume should return null");
    }

    @Test
    void drainWaitsForInFlightRender() throws Exception {
        renderer = new AsyncBitmapRenderer();
        renderer.registerZone("test", 4, 4);
        BlockingPattern blocking = new BlockingPattern();
        AudioState audio = AudioState.silent();

        renderer.submitRender("test", blocking, audio, 1.0);
        assertTrue(blocking.renderStarted.await(2, TimeUnit.SECONDS));

        new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            blocking.allowFinish.countDown();
        }).start();

        long start = System.nanoTime();
        renderer.drainZone("test");
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsed >= 30, "Should have waited for render: " + elapsed + "ms");
        assertFalse(renderer.isRendering("test"));
    }

    @Test
    void renderTimerIsPopulated() throws Exception {
        renderer = new AsyncBitmapRenderer();
        renderer.registerZone("test", 4, 4);
        AudioState audio = AudioState.silent();

        renderer.submitRender("test", new SolidPattern(0xFFFF0000), audio, 1.0);
        Thread.sleep(200);

        BitmapRenderTimer timer = renderer.getTimer("test");
        assertNotNull(timer);
        assertEquals(1, timer.getFrameCount());
        assertTrue(timer.getMaxRenderNanos() > 0);
    }
}
