# Bitmap Async Rendering & Frame Budget Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Eliminate bitmap pattern sync issues by offloading expensive per-pixel computation to an async thread with double-buffering and timing instrumentation.

**Architecture:** The expensive `pattern.render()` call (and transition blending) moves to a single-thread `ExecutorService`, writing into a per-zone back buffer. The main Bukkit thread only consumes completed frames, runs lightweight post-processing (effects, game state), and pushes to entities. If the async render hasn't completed by the next tick, the main thread skips that zone's update — automatic frame dropping with zero main-thread blocking.

**Tech Stack:** Java 21, Paper API 1.21.1+, JUnit 5, Mockito

---

## Root Cause (for context)

Tier 3 bitmap patterns (BitmapFractalZoom, BitmapGalaxy, etc.) perform O(W*H*40) floating-point operations per tick on the main Bukkit thread. At `max_pixels_per_zone: 2000`, this overruns the 50ms tick budget, causing TPS drops, audio-visual desync, and entity update floods (40k metadata changes/sec).

## Design Decisions

1. **Single render thread** shared across all zones (not thread-per-zone) — keeps pattern state access sequential
2. **Pattern rendering + transitions** run async. Post-processing (EffectsProcessor, GameStateModulator) stays on main thread since they're lightweight and have shared mutable state
3. **Graceful fallback**: if the render thread is busy, the main thread simply skips the frame — no blocking, no queuing
4. **Pattern switching safety**: instant-cut switches drain the in-flight async render before swapping pattern references
5. **Transition awareness**: during transitions, the async thread runs `TransitionManager.tick()` (no Bukkit dependencies)

---

### Task 1: Render Timing Instrumentation

Add per-zone render time tracking to `BitmapPatternManager` so we can measure the problem before and after the fix.

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/bitmap/BitmapRenderTimingTest.java`

**Step 1: Write the failing test**

Create `minecraft_plugin/src/test/java/com/audioviz/bitmap/BitmapRenderTimingTest.java`:

```java
package com.audioviz.bitmap;

import com.audioviz.patterns.AudioState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for render timing tracking in the bitmap pipeline.
 */
class BitmapRenderTimingTest {

    /** Stub pattern with controllable render time. */
    static class SlowPattern extends BitmapPattern {
        final long sleepMs;
        SlowPattern(long sleepMs) {
            super("slow_" + sleepMs, "Slow " + sleepMs + "ms", "test pattern");
            this.sleepMs = sleepMs;
        }
        @Override
        public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
            if (sleepMs > 0) {
                long start = System.nanoTime();
                // Busy-wait instead of Thread.sleep for sub-ms accuracy
                while ((System.nanoTime() - start) / 1_000_000 < sleepMs) {
                    Thread.onSpinWait();
                }
            }
            buffer.fill(0xFFFF0000);
        }
    }

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
```

**Step 2: Run test to verify it fails**

Run: `cd minecraft_plugin && mvn test -pl . -Dtest=BitmapRenderTimingTest -DfailIfNoTests=false`
Expected: FAIL — `BitmapRenderTimer` class does not exist

**Step 3: Implement BitmapRenderTimer**

Create `minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapRenderTimer.java`:

```java
package com.audioviz.bitmap;

/**
 * Tracks per-zone bitmap render timing for diagnostics.
 *
 * <p>Records render durations and skipped frames. Provides snapshot-and-reset
 * for periodic logging without allocating on the hot path.
 */
public class BitmapRenderTimer {

    private long totalRenderNanos;
    private long maxRenderNanos;
    private int frameCount;
    private int skipCount;

    public void recordRender(long nanos) {
        totalRenderNanos += nanos;
        if (nanos > maxRenderNanos) {
            maxRenderNanos = nanos;
        }
        frameCount++;
    }

    public void recordSkip() {
        skipCount++;
    }

    public int getFrameCount() { return frameCount; }
    public int getSkipCount() { return skipCount; }
    public long getMaxRenderNanos() { return maxRenderNanos; }

    public long getAvgRenderNanos() {
        return frameCount > 0 ? totalRenderNanos / frameCount : 0;
    }

    /**
     * Snapshot current stats and reset counters.
     */
    public Stats snapshotAndReset() {
        Stats stats = new Stats(frameCount, skipCount, totalRenderNanos,
                                maxRenderNanos, getAvgRenderNanos());
        totalRenderNanos = 0;
        maxRenderNanos = 0;
        frameCount = 0;
        skipCount = 0;
        return stats;
    }

    public record Stats(int frameCount, int skipCount, long totalNanos,
                         long maxNanos, long avgNanos) {
        public double maxMs() { return maxNanos / 1_000_000.0; }
        public double avgMs() { return avgNanos / 1_000_000.0; }
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd minecraft_plugin && mvn test -pl . -Dtest=BitmapRenderTimingTest`
Expected: PASS

**Step 5: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapRenderTimer.java \
       minecraft_plugin/src/test/java/com/audioviz/bitmap/BitmapRenderTimingTest.java
git commit -m "feat(bitmap): add BitmapRenderTimer for per-zone render diagnostics"
```

---

### Task 2: Wire Timing Into BitmapPatternManager

Add timing instrumentation and periodic diagnostic logging to the existing render loop.

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java`

**Step 1: Add timer fields to ZoneState**

In `BitmapPatternManager.java`, modify the `ZoneState` inner class:

```java
private static class ZoneState {
    BitmapPattern pattern;
    BitmapPattern pendingPattern;
    final BitmapFrameBuffer buffer;
    final BitmapRenderTimer timer = new BitmapRenderTimer(); // ADD

    ZoneState(BitmapPattern pattern, BitmapFrameBuffer buffer) {
        this.pattern = pattern;
        this.buffer = buffer;
    }
}
```

**Step 2: Add timing around pattern rendering in tick()**

Wrap the pattern rendering (Step 1 in the tick loop) with `System.nanoTime()`:

```java
// In tick(), inside the per-zone loop, replace Step 1:
long renderStart = System.nanoTime();

// Step 1: Pattern rendering (with transition support)
if (transitionManager.isTransitioning(zoneName)) {
    boolean stillActive = transitionManager.tick(
        zoneName, state.buffer, audio, time);
    if (!stillActive && state.pendingPattern != null) {
        state.pattern = state.pendingPattern;
        state.pendingPattern = null;
    }
} else {
    state.buffer.clear();
    state.pattern.render(state.buffer, audio, time);
}

long renderNanos = System.nanoTime() - renderStart;
state.timer.recordRender(renderNanos);
```

**Step 3: Add periodic logging (every 100 ticks = 5 seconds)**

Add a tick counter field to `BitmapPatternManager`:

```java
private int diagnosticTickCounter = 0;
```

At the end of `tick()`, after the zone loop:

```java
diagnosticTickCounter++;
if (diagnosticTickCounter >= 100) {
    diagnosticTickCounter = 0;
    logRenderDiagnostics();
}
```

Add the logging method:

```java
private void logRenderDiagnostics() {
    for (Map.Entry<String, ZoneState> entry : zoneStates.entrySet()) {
        BitmapRenderTimer.Stats stats = entry.getValue().timer.snapshotAndReset();
        if (stats.frameCount() == 0 && stats.skipCount() == 0) continue;

        String msg = String.format(
            "Bitmap '%s' [%s]: %d frames, %d skipped | avg=%.1fms max=%.1fms",
            entry.getKey(),
            entry.getValue().pattern.getId(),
            stats.frameCount(), stats.skipCount(),
            stats.avgMs(), stats.maxMs());

        if (stats.maxMs() > 25.0) {
            plugin.getLogger().warning(msg + " [SLOW - exceeds 25ms budget]");
        } else {
            plugin.getLogger().info(msg);
        }
    }
}
```

**Step 4: Build and verify**

Run: `cd minecraft_plugin && mvn package -DskipTests`
Expected: BUILD SUCCESS

**Step 5: Run all bitmap tests**

Run: `cd minecraft_plugin && mvn test -pl . -Dtest="com.audioviz.bitmap.*Test"`
Expected: All existing tests pass

**Step 6: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java
git commit -m "feat(bitmap): wire render timing into BitmapPatternManager tick loop"
```

---

### Task 3: Async Render Pipeline — Tests

Write tests for the async rendering behavior before implementing it.

**Files:**
- Create: `minecraft_plugin/src/test/java/com/audioviz/bitmap/AsyncBitmapRendererTest.java`

**Step 1: Write tests for the async renderer**

```java
package com.audioviz.bitmap;

import com.audioviz.patterns.AudioState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for async bitmap rendering with double-buffering.
 */
class AsyncBitmapRendererTest {

    private AsyncBitmapRenderer renderer;

    @AfterEach
    void tearDown() {
        if (renderer != null) renderer.shutdown();
    }

    /** Pattern that fills buffer with a solid color. */
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

    /** Pattern that blocks until a latch is released. */
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

        // Wait for async render to complete
        Thread.sleep(100);

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

        // First submit — starts rendering
        assertTrue(renderer.submitRender("test", blocking, audio, 1.0));
        assertTrue(blocking.renderStarted.await(2, TimeUnit.SECONDS));

        // Second submit while first is still rendering — should be skipped
        assertFalse(renderer.submitRender("test", new SolidPattern(0xFF0000FF), audio, 2.0));

        // Release the blocking render
        blocking.allowFinish.countDown();
    }

    @Test
    void consumeClearsCompletedFrame() throws Exception {
        renderer = new AsyncBitmapRenderer();
        renderer.registerZone("test", 4, 4);
        AudioState audio = AudioState.silent();

        renderer.submitRender("test", new SolidPattern(0xFFFF0000), audio, 1.0);
        Thread.sleep(100);

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

        // Release after short delay (on another thread)
        new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            blocking.allowFinish.countDown();
        }).start();

        // drain should block until render completes
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
        Thread.sleep(100);

        BitmapRenderTimer timer = renderer.getTimer("test");
        assertNotNull(timer);
        assertEquals(1, timer.getFrameCount());
        assertTrue(timer.getMaxRenderNanos() > 0);
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `cd minecraft_plugin && mvn test -pl . -Dtest=AsyncBitmapRendererTest -DfailIfNoTests=false`
Expected: FAIL — `AsyncBitmapRenderer` class does not exist

**Step 3: Commit test file**

```bash
git add minecraft_plugin/src/test/java/com/audioviz/bitmap/AsyncBitmapRendererTest.java
git commit -m "test(bitmap): add AsyncBitmapRenderer tests (red)"
```

---

### Task 4: Async Render Pipeline — Implementation

Implement `AsyncBitmapRenderer` to satisfy the tests from Task 3.

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/bitmap/AsyncBitmapRenderer.java`

**Step 1: Implement AsyncBitmapRenderer**

```java
package com.audioviz.bitmap;

import com.audioviz.bitmap.transitions.TransitionManager;
import com.audioviz.patterns.AudioState;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Offloads bitmap pattern rendering to a dedicated thread.
 *
 * <p>Each zone has a back buffer that the render thread writes into.
 * When rendering completes, the pixel data is published as a snapshot.
 * The main Bukkit thread consumes completed frames and pushes to entities.
 *
 * <p>If the render thread is still busy when a new frame is requested,
 * the request is silently dropped — automatic frame rate adaptation.
 */
public class AsyncBitmapRenderer {

    private final ExecutorService renderThread;
    private final Map<String, ZoneRenderState> zones = new ConcurrentHashMap<>();
    private Logger logger;

    public AsyncBitmapRenderer() {
        this(null);
    }

    public AsyncBitmapRenderer(Logger logger) {
        this.logger = logger;
        this.renderThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mcav-bitmap-render");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Register a zone for async rendering.
     */
    public void registerZone(String zoneName, int width, int height) {
        zones.put(zoneName.toLowerCase(), new ZoneRenderState(width, height));
    }

    /**
     * Remove a zone from async rendering.
     */
    public void removeZone(String zoneName) {
        zones.remove(zoneName.toLowerCase());
    }

    /**
     * Submit a pattern render for a zone.
     *
     * @return true if submitted, false if skipped (previous render still in-flight)
     */
    public boolean submitRender(String zoneName, BitmapPattern pattern,
                                 AudioState audio, double time) {
        return submitRender(zoneName, pattern, null, null, audio, time);
    }

    /**
     * Submit a render that may involve a transition.
     *
     * @param transitionManager if non-null and transitioning, delegates to its tick()
     * @param transitionZoneKey the zone key for the transition manager lookup
     */
    public boolean submitRender(String zoneName, BitmapPattern pattern,
                                 TransitionManager transitionManager,
                                 String transitionZoneKey,
                                 AudioState audio, double time) {
        ZoneRenderState state = zones.get(zoneName.toLowerCase());
        if (state == null) return false;

        // Skip if previous render is still running
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

                // Publish completed frame as a snapshot
                int[] snapshot = new int[state.backBuffer.getPixelCount()];
                System.arraycopy(state.backBuffer.getRawPixels(), 0,
                                 snapshot, 0, snapshot.length);
                state.completedPixels.set(snapshot);
            } catch (Exception e) {
                if (logger != null) {
                    logger.warning("Async bitmap render error [" + zoneName + "]: " + e.getMessage());
                }
            } finally {
                long elapsed = System.nanoTime() - startNanos;
                state.timer.recordRender(elapsed);
                state.rendering.set(false);
            }
        });

        return true;
    }

    /**
     * Consume the latest completed frame for a zone.
     *
     * @return pixel snapshot (row-major ARGB), or null if no new frame
     */
    public int[] consumeCompletedFrame(String zoneName) {
        ZoneRenderState state = zones.get(zoneName.toLowerCase());
        if (state == null) return null;
        return state.completedPixels.getAndSet(null);
    }

    /**
     * Check if a zone is currently rendering.
     */
    public boolean isRendering(String zoneName) {
        ZoneRenderState state = zones.get(zoneName.toLowerCase());
        return state != null && state.rendering.get();
    }

    /**
     * Block until any in-flight render for this zone completes.
     * Used during pattern switching to ensure clean handoff.
     */
    public void drainZone(String zoneName) {
        ZoneRenderState state = zones.get(zoneName.toLowerCase());
        if (state == null) return;

        // Spin-wait with yield (typically < 50ms)
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (state.rendering.get() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    /**
     * Get the render timer for a zone.
     */
    public BitmapRenderTimer getTimer(String zoneName) {
        ZoneRenderState state = zones.get(zoneName.toLowerCase());
        return state != null ? state.timer : null;
    }

    /**
     * Shut down the render thread.
     */
    public void shutdown() {
        renderThread.shutdownNow();
        zones.clear();
    }

    /** Per-zone async render state. */
    private static class ZoneRenderState {
        final BitmapFrameBuffer backBuffer;
        final AtomicReference<int[]> completedPixels = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean rendering =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        final BitmapRenderTimer timer = new BitmapRenderTimer();

        ZoneRenderState(int width, int height) {
            this.backBuffer = new BitmapFrameBuffer(width, height);
        }
    }
}
```

**Step 2: Run tests**

Run: `cd minecraft_plugin && mvn test -pl . -Dtest=AsyncBitmapRendererTest`
Expected: PASS

**Step 3: Run all bitmap tests for regression**

Run: `cd minecraft_plugin && mvn test -pl . -Dtest="com.audioviz.bitmap.*Test"`
Expected: All pass

**Step 4: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/AsyncBitmapRenderer.java
git commit -m "feat(bitmap): implement AsyncBitmapRenderer with double-buffering"
```

---

### Task 5: Integrate Async Renderer Into BitmapPatternManager

Replace the synchronous render loop with the async pipeline.

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java`

**Step 1: Add AsyncBitmapRenderer field and lifecycle**

Add field:
```java
private final AsyncBitmapRenderer asyncRenderer;
```

In constructor, after `registerBuiltInPatterns()`:
```java
this.asyncRenderer = new AsyncBitmapRenderer(plugin.getLogger());
```

In `shutdown()`, add before `zoneStates.clear()`:
```java
asyncRenderer.shutdown();
```

**Step 2: Register zones with async renderer**

In `activateZone()`, after `zoneStates.put(...)`:
```java
asyncRenderer.registerZone(zoneName, width, height);
```

In `deactivateZone()`, after `zoneStates.remove(key)`:
```java
asyncRenderer.removeZone(zoneName);
```

**Step 3: Thread-safe pattern switching**

In `setPattern()` (the 4-arg overload), for the instant-cut branch, add a drain before the swap:

```java
} else {
    // Instant cut — drain in-flight async render first
    asyncRenderer.drainZone(zoneName);
    state.pattern.reset();
    state.pattern = newPattern;
    newPattern.reset();
    state.buffer.clear();
}
```

**Step 4: Replace synchronous tick with async pipeline**

Replace the existing `tick()` method body with:

```java
public void tick(AudioState audio) {
    if (zoneStates.isEmpty()) return;

    long now = System.currentTimeMillis();
    double time = (now - startTimeMs) / 1000.0;
    double dt = (now - lastTickMs) / 1000.0;
    lastTickMs = now;

    // Refresh game state on main thread (throttled internally to every ~1s)
    gameStateModulator.refreshWorldState();

    for (Map.Entry<String, ZoneState> entry : zoneStates.entrySet()) {
        String zoneName = entry.getKey();
        ZoneState state = entry.getValue();

        try {
            // Submit async render (skipped if previous render still in-flight)
            asyncRenderer.submitRender(
                zoneName, state.pattern,
                transitionManager, zoneName,
                audio, time);

            // Consume completed frame (null if async render hasn't finished)
            int[] completedPixels = asyncRenderer.consumeCompletedFrame(zoneName);
            if (completedPixels != null) {
                // Handle transition completion
                if (state.pendingPattern != null
                        && !transitionManager.isTransitioning(zoneName)) {
                    state.pattern = state.pendingPattern;
                    state.pendingPattern = null;
                }

                // Load rendered pixels into main-thread buffer
                System.arraycopy(completedPixels, 0,
                    state.buffer.getRawPixels(), 0,
                    Math.min(completedPixels.length, state.buffer.getPixelCount()));

                // Post-processing on main thread (lightweight)
                gameStateModulator.modulate(state.buffer, dt);
                effectsProcessor.process(state.buffer, audio, time);

                // Push to entities (main thread required)
                renderer.applyFrame(zoneName, state.buffer);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error in bitmap zone '" + zoneName
                + "' pattern '" + state.pattern.getId() + "': " + e.getMessage());
        }
    }

    // Periodic diagnostics
    diagnosticTickCounter++;
    if (diagnosticTickCounter >= 100) {
        diagnosticTickCounter = 0;
        logRenderDiagnostics();
    }
}
```

**Step 5: Update diagnostic logging to use async timer**

Replace the `logRenderDiagnostics()` method:

```java
private void logRenderDiagnostics() {
    for (Map.Entry<String, ZoneState> entry : zoneStates.entrySet()) {
        String zoneName = entry.getKey();
        BitmapRenderTimer timer = asyncRenderer.getTimer(zoneName);
        if (timer == null) continue;

        BitmapRenderTimer.Stats stats = timer.snapshotAndReset();
        if (stats.frameCount() == 0 && stats.skipCount() == 0) continue;

        String msg = String.format(
            "Bitmap '%s' [%s]: %d frames, %d skipped | avg=%.1fms max=%.1fms",
            zoneName,
            entry.getValue().pattern.getId(),
            stats.frameCount(), stats.skipCount(),
            stats.avgMs(), stats.maxMs());

        if (stats.maxMs() > 25.0) {
            plugin.getLogger().warning(msg + " [SLOW - exceeds 25ms budget]");
        } else {
            plugin.getLogger().info(msg);
        }
    }
}
```

**Step 6: Remove the ZoneState timer field**

Since timing is now in `AsyncBitmapRenderer`, remove the `timer` field from `ZoneState`:

```java
private static class ZoneState {
    BitmapPattern pattern;
    BitmapPattern pendingPattern;
    final BitmapFrameBuffer buffer;

    ZoneState(BitmapPattern pattern, BitmapFrameBuffer buffer) {
        this.pattern = pattern;
        this.buffer = buffer;
    }
}
```

**Step 7: Build**

Run: `cd minecraft_plugin && mvn package -DskipTests`
Expected: BUILD SUCCESS

**Step 8: Run all tests**

Run: `cd minecraft_plugin && mvn test`
Expected: All pass (some tests may need adjusting if they directly call `tick()` — check output)

**Step 9: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java
git commit -m "feat(bitmap): integrate async renderer into BitmapPatternManager

Offloads pattern.render() and transition blending to a dedicated thread.
Main thread only consumes completed frames and applies post-processing.
Frames are automatically dropped if the render thread can't keep up."
```

---

### Task 6: Reduce BitmapFractalZoom Computation Cost

Complementary optimization: reduce the per-pixel iteration cap so the pattern runs faster even on the async thread (producing fewer dropped frames).

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/bitmap/patterns/BitmapFractalZoom.java`

**Step 1: Reduce MAX_ITER from 40 to 24**

The visual difference between 40 and 24 iterations is minimal at the resolutions we render (up to ~45x45 pixels). The boundary detail that 40 iterations provides is invisible at low resolution.

In `BitmapFractalZoom.java`, change:
```java
private static final int MAX_ITER = 24;
```

**Step 2: Precompute sin/cos outside the pixel loop**

Move the `Math.sin`/`Math.cos` calls that depend only on `time` before the pixel loops:

```java
@Override
public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
    int w = buffer.getWidth();
    int h = buffer.getHeight();

    double bass = audio.getBass();
    double mid = audio.getMid();
    double amplitude = audio.getAmplitude();

    if (audio.isBeat()) {
        beatPulse = 1.0;
    }
    beatPulse *= 0.9;

    // Continuous zoom
    zoom *= 1.0 + (0.002 + bass * 0.01 + beatPulse * 0.05);
    if (zoom > 1000) {
        zoom = 1.0;
        centerX = -0.5 + Math.sin(time * 0.1) * 0.3;
        centerY = Math.cos(time * 0.13) * 0.3;
    }

    // Precompute time-dependent values
    double cRe = -0.7 + Math.sin(time * 0.15) * 0.15 + mid * 0.05;
    double cIm = 0.27 + Math.cos(time * 0.12) * 0.1 + bass * 0.03;
    double scale = 3.0 / zoom;
    double hueShift = time * 20;
    double halfW = w / 2.0;
    double halfH = h / 2.0;
    double invW = scale / w;
    double invH = scale / h;
    double log2 = Math.log(2);

    for (int py = 0; py < h; py++) {
        double y0 = centerY + (py - halfH) * invH;
        for (int px = 0; px < w; px++) {
            double x0 = centerX + (px - halfW) * invW;

            double zr = x0, zi = y0;
            int iter = 0;
            while (iter < MAX_ITER && zr * zr + zi * zi < 4.0) {
                double tmp = zr * zr - zi * zi + cRe;
                zi = 2.0 * zr * zi + cIm;
                zr = tmp;
                iter++;
            }

            if (iter < MAX_ITER) {
                double mag = Math.sqrt(zr * zr + zi * zi);
                double smoothIter = iter + 1 - Math.log(Math.log(Math.max(1.0001, mag))) / log2;
                float hue = (float) ((smoothIter * 8 + hueShift) % 360);
                float sat = 0.8f + (float) (amplitude * 0.2);
                float bri = (float) Math.min(1.0, 0.5 + amplitude * 0.3 + beatPulse * 0.2);
                buffer.setPixel(px, py, BitmapFrameBuffer.fromHSB(hue, sat, bri));
            }
        }
    }
}
```

Key optimizations:
- `MAX_ITER` 40 → 24 (~40% fewer iterations)
- Precompute `invW`, `invH`, `halfW`, `halfH`, `log2` outside loops
- Hoist `Math.max()` into `Math.sqrt()` already guarantees >= 0

**Step 3: Build and verify**

Run: `cd minecraft_plugin && mvn package -DskipTests`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/patterns/BitmapFractalZoom.java
git commit -m "perf(bitmap): reduce BitmapFractalZoom MAX_ITER 40→24, precompute invariants"
```

---

### Task 7: Verify Full Build

Final verification that everything compiles and tests pass together.

**Step 1: Full build**

Run: `cd minecraft_plugin && mvn clean package`
Expected: BUILD SUCCESS with all tests passing

**Step 2: Review diagnostics output**

Deploy to test server and switch to `bmp_fractal` pattern. Observe server logs for:
- `Bitmap 'main' [bmp_fractal]: N frames, M skipped | avg=Xms max=Yms`
- The "skipped" count should be low (< 20% of frames)
- avg render time should be < 25ms
- No `[SLOW]` warnings under normal load

**Step 3: Verify sync behavior**

- Audio-visual sync should be noticeably improved
- No visible tearing (all entity updates happen in one main-thread tick)
- TPS should stay at 20 (check with `/tps` or Spark)

---

## Summary of Changes

| File | Change |
|-|-|
| `BitmapRenderTimer.java` | New — per-zone timing stats |
| `AsyncBitmapRenderer.java` | New — async render with double-buffering |
| `BitmapPatternManager.java` | Modified — uses async pipeline, periodic diagnostics |
| `BitmapFractalZoom.java` | Modified — MAX_ITER 40→24, precomputed invariants |
| `BitmapRenderTimingTest.java` | New — timer unit tests |
| `AsyncBitmapRendererTest.java` | New — async renderer tests |

## Rollback

If async rendering causes issues, the change is isolated to `BitmapPatternManager.tick()`. Reverting Task 5 restores the synchronous pipeline. Tasks 1-4 and 6 are independently useful regardless.
