package com.audioviz.bitmap;

import com.audioviz.bitmap.transitions.TransitionManager;
import com.audioviz.patterns.AudioState;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Offloads bitmap pattern rendering to a dedicated thread.
 *
 * <p>Each zone has a back buffer that the render thread writes into.
 * When rendering completes, the pixel data is published as a snapshot.
 * The main server thread consumes completed frames and pushes to entities.
 *
 * <p>If the render thread is still busy when a new frame is requested,
 * the request is silently dropped — automatic frame rate adaptation.
 */
public class AsyncBitmapRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz");

    private final ExecutorService renderThread;
    private final Map<String, ZoneRenderState> zones = new ConcurrentHashMap<>();

    public AsyncBitmapRenderer() {
        this.renderThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mcav-bitmap-render");
            t.setDaemon(true);
            return t;
        });
    }

    public void registerZone(String zoneName, int width, int height) {
        zones.put(zoneName.toLowerCase(), new ZoneRenderState(width, height));
    }

    public void removeZone(String zoneName) {
        zones.remove(zoneName.toLowerCase());
    }

    public boolean submitRender(String zoneName, BitmapPattern pattern,
                                 AudioState audio, double time) {
        return submitRender(zoneName, pattern, null, null, audio, time);
    }

    public boolean submitRender(String zoneName, BitmapPattern pattern,
                                 TransitionManager transitionManager,
                                 String transitionZoneKey,
                                 AudioState audio, double time) {
        ZoneRenderState state = zones.get(zoneName.toLowerCase());
        if (state == null) return false;

        if (!state.rendering.compareAndSet(false, true)) {
            state.timer.recordSkip();
            return false;
        }

        renderThread.submit(() -> {
            long startNanos = System.nanoTime();
            try {
                if (transitionManager != null
                        && transitionZoneKey != null
                        && transitionManager.isTransitioning(transitionZoneKey)) {
                    transitionManager.tick(transitionZoneKey, state.backBuffer, audio, time);
                } else {
                    state.backBuffer.clear();
                    pattern.render(state.backBuffer, audio, time);
                }

                int[] snapshot = new int[state.backBuffer.getPixelCount()];
                System.arraycopy(state.backBuffer.getRawPixels(), 0,
                                 snapshot, 0, snapshot.length);
                state.completedPixels.set(snapshot);
            } catch (Exception e) {
                LOGGER.warn("Async bitmap render error [{}]: {}", zoneName, e.getMessage());
            } finally {
                long elapsed = System.nanoTime() - startNanos;
                state.timer.recordRender(elapsed);
                state.rendering.set(false);
            }
        });

        return true;
    }

    public int[] consumeCompletedFrame(String zoneName) {
        ZoneRenderState state = zones.get(zoneName.toLowerCase());
        if (state == null) return null;
        return state.completedPixels.getAndSet(null);
    }

    public boolean isRendering(String zoneName) {
        ZoneRenderState state = zones.get(zoneName.toLowerCase());
        return state != null && state.rendering.get();
    }

    public void drainZone(String zoneName) {
        ZoneRenderState state = zones.get(zoneName.toLowerCase());
        if (state == null) return;

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (state.rendering.get() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    public BitmapRenderTimer getTimer(String zoneName) {
        ZoneRenderState state = zones.get(zoneName.toLowerCase());
        return state != null ? state.timer : null;
    }

    public void shutdown() {
        renderThread.shutdownNow();
        zones.clear();
    }

    private static class ZoneRenderState {
        final BitmapFrameBuffer backBuffer;
        final AtomicReference<int[]> completedPixels = new AtomicReference<>();
        final AtomicBoolean rendering = new AtomicBoolean(false);
        final BitmapRenderTimer timer = new BitmapRenderTimer();

        ZoneRenderState(int width, int height) {
            this.backBuffer = new BitmapFrameBuffer(width, height);
        }
    }
}
