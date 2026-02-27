package com.audioviz.bitmap;

import com.audioviz.bitmap.effects.EffectsProcessor;
import com.audioviz.bitmap.gamestate.*;
import com.audioviz.bitmap.media.*;
import com.audioviz.bitmap.patterns.*;
import com.audioviz.bitmap.text.*;
import com.audioviz.bitmap.transitions.TransitionManager;
import com.audioviz.patterns.AudioState;
import com.audioviz.render.MapRendererBackend;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Manages bitmap patterns, frame buffers, and the render loop for bitmap zones.
 *
 * <p>For each bitmap zone, this manager:
 * <ol>
 *   <li>Maintains a {@link BitmapFrameBuffer} sized to the zone's grid dimensions</li>
 *   <li>Runs the active {@link BitmapPattern} each tick to write into the buffer</li>
 *   <li>Applies transitions when switching patterns (via {@link TransitionManager})</li>
 *   <li>Runs post-processing effects (via {@link EffectsProcessor})</li>
 *   <li>Applies game state modulation (time of day, weather, crowd energy)</li>
 *   <li>Pushes the buffer to {@link MapRendererBackend#applyFrame} for map updates</li>
 * </ol>
 *
 * <p>Ported from Paper: BukkitTask → tick-based (called from AudioVizMod),
 * AudioVizPlugin → MinecraftServer, plugin.getLogger() → SLF4J.
 */
public class BitmapPatternManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz");
    private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder();
    private static final int BROADCAST_INTERVAL_TICKS = 3; // ~7 FPS

    private final MinecraftServer server;
    private final MapRendererBackend renderer;
    private final AsyncBitmapRenderer asyncRenderer;

    /** All registered bitmap patterns, keyed by pattern ID. */
    private final Map<String, BitmapPattern> patternRegistry = new LinkedHashMap<>();

    /** Per-zone state: active pattern + frame buffer. */
    private final Map<String, ZoneState> zoneStates = new ConcurrentHashMap<>();

    /** Time tracking for pattern animation. */
    private final long startTimeMs = System.currentTimeMillis();
    private long lastTickMs = System.currentTimeMillis();
    private int diagnosticTickCounter = 0;

    /** Transition engine (shared across zones). */
    private final TransitionManager transitionManager = new TransitionManager();

    /** Post-processing effects (shared across zones). */
    private final EffectsProcessor effectsProcessor = new EffectsProcessor();

    /** Game state modulator. */
    private final GameStateModulator gameStateModulator;

    /** Optional callback for broadcasting rendered frames to WebSocket clients. */
    private volatile Consumer<JsonObject> frameBroadcaster;

    /** Supplier for checking connected client count (avoids broadcasting to nobody). */
    private volatile IntSupplier connectionCountSupplier;

    /** Off-thread executor for frame encoding + broadcast. */
    private final ExecutorService broadcastExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audioviz-bitmap-broadcast");
        t.setDaemon(true);
        return t;
    });

    /** Frame throttle counter — only broadcast every BROADCAST_INTERVAL_TICKS ticks. */
    private int broadcastTickCounter = 0;

    /** Latest audio state from VJ server. */
    private volatile AudioState latestAudioState = AudioState.silent();

    public BitmapPatternManager(MinecraftServer server, MapRendererBackend renderer) {
        this.server = server;
        this.renderer = renderer;
        this.gameStateModulator = new GameStateModulator(server);
        registerBuiltInPatterns();
        this.asyncRenderer = new AsyncBitmapRenderer();
    }

    public void setFrameBroadcaster(Consumer<JsonObject> broadcaster) {
        this.frameBroadcaster = broadcaster;
    }

    public void setConnectionCountSupplier(IntSupplier supplier) {
        this.connectionCountSupplier = supplier;
    }

    /**
     * Update the audio state used by the self-tick loop.
     * Called from message handlers when audio data arrives.
     */
    public void updateAudioState(AudioState audio) {
        this.latestAudioState = audio;
    }

    // ========== Pattern Registry ==========

    private void registerBuiltInPatterns() {
        // --- Active patterns: visually impressive, audio-reactive, high-res ready ---

        // Core audio-reactive
        register(new BitmapSpectrumBars());
        register(new BitmapSpectrogram());
        register(new BitmapPlasma());
        register(new BitmapCircularSpectrum());

        // Festival staples
        register(new BitmapFire());
        register(new BitmapMatrixRain());
        register(new BitmapStarfield());
        register(new BitmapConcentricRings());
        register(new BitmapAurora());
        register(new BitmapTunnelZoom());

        // Advanced effects
        register(new BitmapKaleidoscope());
        register(new BitmapGalaxy());
        register(new BitmapLightning());
        register(new BitmapRotatingGeometry());

        // --- Disabled: need rework for high-res or are situational ---
        // register(new BitmapWaveform());
        // register(new BitmapVUMeter());
        // register(new BitmapRadialBurst());
        // register(new BitmapColorWash());
        // register(new BitmapCheckerboardFlash());
        // register(new BitmapParticleRain());
        // register(new BitmapGridWarp());
        // register(new BitmapMoire());
        // register(new BitmapPixelSort());
        // register(new BitmapRipple());
        // register(new BitmapScanLines());
        // register(new BitmapWavePropagation());
        // register(new BitmapFractalZoom());
        // register(new BitmapInkDrop());
        // register(new BitmapDataMosh());
        // register(new BitmapTerrain());
        // register(new BitmapHexGrid());
        // register(new BitmapFireflies());
        // register(new BitmapDigitalNoise());

        // --- Disabled: require external data / special setup ---
        // register(new MarqueePattern());
        // register(new TrackDisplayPattern());
        // register(new CountdownPattern());
        // register(new ChatWallPattern());
        // register(new CrowdCamPattern(server));
        // register(new MinimapPattern(server));
        // register(new FireworkPattern());
        // register(new ImagePattern());
        // register(new DJLogoPattern());
    }

    public void register(BitmapPattern pattern) {
        patternRegistry.put(pattern.getId(), pattern);
    }

    public List<String> getPatternIds() {
        return new ArrayList<>(patternRegistry.keySet());
    }

    public BitmapPattern getPattern(String id) {
        return patternRegistry.get(id);
    }

    public Collection<BitmapPattern> getAllPatterns() {
        return Collections.unmodifiableCollection(patternRegistry.values());
    }

    // ========== Subsystem Accessors ==========

    public TransitionManager getTransitionManager() { return transitionManager; }
    public EffectsProcessor getEffectsProcessor() { return effectsProcessor; }
    public GameStateModulator getGameStateModulator() { return gameStateModulator; }

    // ========== Zone Management ==========

    public void activateZone(String zoneName, String patternId, int width, int height) {
        BitmapPattern pattern = patternRegistry.get(patternId);
        if (pattern == null) {
            LOGGER.warn("Unknown bitmap pattern: {}. Using bmp_spectrum as default.", patternId);
            pattern = patternRegistry.get("bmp_spectrum");
            if (pattern == null) {
                LOGGER.error("No bitmap patterns registered!");
                return;
            }
        }

        BitmapFrameBuffer buffer = new BitmapFrameBuffer(width, height);
        zoneStates.put(zoneName.toLowerCase(), new ZoneState(pattern, buffer));
        asyncRenderer.registerZone(zoneName, width, height);

        LOGGER.info("Bitmap zone '{}' activated with pattern '{}' ({}x{})",
            zoneName, pattern.getId(), width, height);
    }

    public void setPattern(String zoneName, String patternId) {
        setPattern(zoneName, patternId, null, 0);
    }

    public void setPattern(String zoneName, String patternId,
                           String transitionId, int durationTicks) {
        String key = zoneName.toLowerCase();
        ZoneState state = zoneStates.get(key);
        if (state == null) {
            LOGGER.warn("Cannot set pattern: bitmap zone '{}' not active", zoneName);
            return;
        }

        BitmapPattern newPattern = patternRegistry.get(patternId);
        if (newPattern == null) {
            LOGGER.warn("Unknown bitmap pattern: {}", patternId);
            return;
        }

        if (durationTicks > 0 && transitionId != null) {
            transitionManager.startTransition(key, state.pattern, newPattern,
                transitionId, durationTicks,
                state.buffer.getWidth(), state.buffer.getHeight());
            state.pendingPattern = newPattern;
        } else {
            asyncRenderer.drainZone(zoneName);
            state.pattern.reset();
            state.pattern = newPattern;
            newPattern.reset();
            state.buffer.clear();
        }

        LOGGER.info("Bitmap zone '{}' {} pattern '{}'",
            zoneName,
            durationTicks > 0 ? "transitioning to" : "switched to",
            patternId);
    }

    public void deactivateZone(String zoneName) {
        String key = zoneName.toLowerCase();
        transitionManager.cancel(key);
        asyncRenderer.drainZone(zoneName);
        ZoneState state = zoneStates.remove(key);
        asyncRenderer.removeZone(zoneName);
        if (state != null) {
            state.pattern.reset();
            if (state.pendingPattern != null) {
                state.pendingPattern.reset();
                state.pendingPattern = null;
            }
        }
    }

    public boolean isActive(String zoneName) {
        return zoneStates.containsKey(zoneName.toLowerCase());
    }

    public String getActivePatternId(String zoneName) {
        ZoneState state = zoneStates.get(zoneName.toLowerCase());
        return state != null ? state.pattern.getId() : null;
    }

    // ========== Render Loop ==========

    /**
     * Tick all active bitmap zones. Called from AudioVizMod's server tick handler.
     */
    public void tick(AudioState audio) {
        if (zoneStates.isEmpty()) return;

        long now = System.currentTimeMillis();
        double time = (now - startTimeMs) / 1000.0;
        double dt = Math.min((now - lastTickMs) / 1000.0, 0.1); // clamp to 100ms max
        lastTickMs = now;

        gameStateModulator.refreshWorldState();

        for (Map.Entry<String, ZoneState> entry : zoneStates.entrySet()) {
            String zoneName = entry.getKey();
            ZoneState state = entry.getValue();

            try {
                asyncRenderer.submitRender(
                    zoneName, state.pattern,
                    transitionManager, zoneName,
                    audio, time);

                int[] completedPixels = asyncRenderer.consumeCompletedFrame(zoneName);
                if (completedPixels != null) {
                    if (state.pendingPattern != null
                            && !transitionManager.isTransitioning(zoneName)) {
                        state.pattern = state.pendingPattern;
                        state.pendingPattern = null;
                    }

                    System.arraycopy(completedPixels, 0,
                        state.buffer.getRawPixels(), 0,
                        Math.min(completedPixels.length, state.buffer.getPixelCount()));

                    gameStateModulator.modulate(state.buffer, dt);
                    effectsProcessor.process(state.buffer, audio, time);

                    renderer.applyFrame(zoneName, state.buffer.getRawPixels(), state.buffer.getWidth(), state.buffer.getHeight());

                    // Broadcast frame to WebSocket clients for browser preview (throttled, off-thread)
                    if (frameBroadcaster != null
                            && broadcastTickCounter % BROADCAST_INTERVAL_TICKS == 0
                            && connectionCountSupplier != null
                            && connectionCountSupplier.getAsInt() > 0) {
                        broadcastFrame(zoneName, state);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Error in bitmap zone '{}' pattern '{}': {}",
                    zoneName, state.pattern.getId(), e.getMessage());
            }
        }

        broadcastTickCounter++;
        diagnosticTickCounter++;
        if (diagnosticTickCounter >= 100) {
            diagnosticTickCounter = 0;
            logRenderDiagnostics();
        }
    }

    /**
     * Encode a frame buffer as a bitmap_frame JSON message and broadcast it.
     * Uses base64-encoded little-endian ARGB int array (matches protocol spec).
     *
     * <p>Copies pixels on the server thread, then submits encoding + broadcast
     * to a dedicated single-thread executor to avoid blocking the tick loop.
     */
    private void broadcastFrame(String zoneName, ZoneState state) {
        try {
            BitmapFrameBuffer buffer = state.buffer;
            int[] pixels = buffer.getRawPixels();
            int pixelCount = buffer.getPixelCount();
            int width = buffer.getWidth();
            int height = buffer.getHeight();

            // Copy pixels for off-thread use
            System.arraycopy(pixels, 0, state.broadcastPixelsCopy, 0, pixelCount);

            broadcastExecutor.execute(() -> {
                try {
                    ByteBuffer bb = state.broadcastByteBuffer;
                    bb.clear();
                    for (int i = 0; i < pixelCount; i++) {
                        bb.putInt(state.broadcastPixelsCopy[i]);
                    }
                    bb.flip();

                    String base64 = BASE64_ENCODER.encodeToString(bb.array());

                    JsonObject msg = new JsonObject();
                    msg.addProperty("type", "bitmap_frame");
                    msg.addProperty("zone", zoneName);
                    msg.addProperty("width", width);
                    msg.addProperty("height", height);
                    msg.addProperty("pixels", base64);

                    frameBroadcaster.accept(msg);
                } catch (Exception e) {
                    LOGGER.warn("Failed to broadcast bitmap frame for zone '{}': {}", zoneName, e.getMessage());
                }
            });
        } catch (Exception e) {
            LOGGER.warn("Failed to prepare bitmap frame broadcast for zone '{}': {}", zoneName, e.getMessage());
        }
    }

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
                LOGGER.warn("{} [SLOW - exceeds 25ms budget]", msg);
            } else {
                LOGGER.info(msg);
            }
        }
    }

    public void shutdown() {
        broadcastExecutor.shutdownNow();
        transitionManager.cancelAll();
        effectsProcessor.reset();
        asyncRenderer.shutdown();
        for (ZoneState state : zoneStates.values()) {
            state.pattern.reset();
        }
        zoneStates.clear();
    }

    public BitmapFrameBuffer getFrameBuffer(String zoneName) {
        ZoneState state = zoneStates.get(zoneName.toLowerCase());
        return state != null ? state.buffer : null;
    }

    // ========== Internal State ==========

    private static class ZoneState {
        BitmapPattern pattern;
        BitmapPattern pendingPattern;
        final BitmapFrameBuffer buffer;
        final int[] broadcastPixelsCopy;
        final ByteBuffer broadcastByteBuffer;

        ZoneState(BitmapPattern pattern, BitmapFrameBuffer buffer) {
            this.pattern = pattern;
            this.buffer = buffer;
            this.broadcastPixelsCopy = new int[buffer.getPixelCount()];
            this.broadcastByteBuffer = ByteBuffer.allocate(buffer.getPixelCount() * 4);
            this.broadcastByteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        }
    }
}
