package com.audioviz.bitmap;

import com.audioviz.AudioVizPlugin;
import com.audioviz.bitmap.effects.EffectsProcessor;
import com.audioviz.bitmap.gamestate.*;
import com.audioviz.bitmap.media.DJLogoPattern;
import com.audioviz.bitmap.media.ImagePattern;
import com.audioviz.bitmap.patterns.*;
import com.audioviz.bitmap.text.*;
import com.audioviz.bitmap.transitions.TransitionManager;
import com.audioviz.patterns.AudioState;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.scheduler.BukkitTask;

import com.google.gson.JsonObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 *   <li>Pushes the buffer to {@link BitmapRendererBackend#applyFrame} for entity updates</li>
 * </ol>
 *
 * <p>Patterns are registered globally and selected per-zone. The VJ server can
 * switch patterns via the WebSocket protocol.
 */
public class BitmapPatternManager {

    private final AudioVizPlugin plugin;
    private final BitmapRendererBackend renderer;
    private final AsyncBitmapRenderer asyncRenderer;

    /** All registered bitmap patterns, keyed by pattern ID. */
    private final Map<String, BitmapPattern> patternRegistry = new LinkedHashMap<>();

    /** Per-zone state: active pattern + frame buffer. */
    private final Map<String, ZoneState> zoneStates = new ConcurrentHashMap<>();

    /** Bitmap frame broadcasting to WebSocket clients for browser preview. */
    private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder();
    private static final int BROADCAST_INTERVAL_TICKS = 3;
    private int broadcastTickCounter = 0;
    private final ExecutorService broadcastExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audioviz-bitmap-broadcast");
        t.setDaemon(true);
        return t;
    });

    /** Time tracking for pattern animation. */
    private final long startTimeMs = System.currentTimeMillis();
    private long lastTickMs = System.currentTimeMillis();
    private int diagnosticTickCounter = 0;

    /** Transition engine (shared across zones). */
    private final TransitionManager transitionManager = new TransitionManager();

    /** Post-processing effects (shared across zones, applied globally). */
    private final EffectsProcessor effectsProcessor = new EffectsProcessor();

    /** Game state modulator (Bukkit-dependent). */
    private final GameStateModulator gameStateModulator;

    /** Latest audio state from VJ server (updated by message handlers). */
    private volatile AudioState latestAudioState = AudioState.silent();

    /** Self-tick scheduler task. */
    private BukkitTask tickTask;

    public BitmapPatternManager(AudioVizPlugin plugin, BitmapRendererBackend renderer) {
        this.plugin = plugin;
        this.renderer = renderer;
        this.gameStateModulator = new GameStateModulator(plugin);
        registerBuiltInPatterns();
        this.asyncRenderer = new AsyncBitmapRenderer(plugin.getLogger());
    }

    /**
     * Start the self-tick scheduler. Runs at 20 TPS on the main thread,
     * independent of VJ server messages, so bitmap patterns animate
     * even without an active DJ or Lua pattern zones.
     */
    public void start() {
        if (tickTask != null) return;
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!zoneStates.isEmpty()) {
                tick(latestAudioState);
            }
            // Capture frame for recording (even if no zones, records audio state)
            var recorder = plugin.getRecordingManager();
            if (recorder != null) {
                recorder.captureFrame(latestAudioState);
            }
        }, 1L, 1L); // Every tick (50ms = 20 TPS)
    }

    /**
     * Update the audio state used by the self-tick loop.
     * Called from message handlers when audio data arrives.
     */
    public void updateAudioState(AudioState audio) {
        var bsm = plugin.getBeatSyncManager();
        if (bsm != null) {
            audio = bsm.applyOverrides(audio);
        }
        this.latestAudioState = audio;
        var listener = plugin.getConnectionStateListener();
        if (listener != null) {
            listener.onAudioFrame();
        }
    }

    // ========== Pattern Registry ==========

    private void registerBuiltInPatterns() {
        // Core audio-reactive patterns (Phase 1)
        register(new BitmapSpectrumBars());
        register(new BitmapSpectrogram());
        register(new BitmapPlasma());
        register(new BitmapWaveform());
        register(new BitmapVUMeter());

        // Tier 1: Festival staples — high impact, low complexity
        register(new BitmapFire());
        register(new BitmapMatrixRain());
        register(new BitmapStarfield());
        register(new BitmapConcentricRings());
        register(new BitmapRadialBurst());
        register(new BitmapColorWash());
        register(new BitmapAurora());
        register(new BitmapTunnelZoom());
        register(new BitmapCheckerboardFlash());
        register(new BitmapParticleRain());

        // Tier 2: Advanced effects — moderate complexity
        register(new BitmapGridWarp());
        register(new BitmapKaleidoscope());
        register(new BitmapMoire());
        register(new BitmapLightning());
        register(new BitmapPixelSort());
        register(new BitmapRipple());
        register(new BitmapCircularSpectrum());
        register(new BitmapScanLines());
        register(new BitmapWavePropagation());

        // Tier 3: Complex simulations — high visual fidelity
        register(new BitmapFractalZoom());
        register(new BitmapInkDrop());
        register(new BitmapDataMosh());
        register(new BitmapTerrain());
        register(new BitmapGalaxy());
        register(new BitmapHexGrid());
        register(new BitmapFireflies());
        register(new BitmapDigitalNoise());
        register(new BitmapRotatingGeometry());

        // Tier 4: Psychedelic / immersive — Sphere-inspired
        register(new BitmapMetaballs());
        register(new BitmapReactionDiffusion());
        register(new BitmapFluid());
        register(new BitmapVoronoiShatter());
        register(new BitmapSacredSpiral());
        register(new BitmapFeedbackLoop());

        // Text patterns (Phase 3)
        register(new MarqueePattern());
        register(new TrackDisplayPattern());
        register(new CountdownPattern());
        register(new ChatWallPattern());

        // Game integration patterns (Phase 3)
        register(new CrowdCamPattern(plugin));
        register(new MinimapPattern(plugin));
        register(new FireworkPattern());

        // Media patterns (Phase 3)
        register(new ImagePattern());
        register(new DJLogoPattern());
    }

    /**
     * Register a bitmap pattern.
     */
    public void register(BitmapPattern pattern) {
        patternRegistry.put(pattern.getId(), pattern);
    }

    /**
     * Get all registered pattern IDs.
     */
    public List<String> getPatternIds() {
        return new ArrayList<>(patternRegistry.keySet());
    }

    /**
     * Get a pattern by ID.
     */
    public BitmapPattern getPattern(String id) {
        return patternRegistry.get(id);
    }

    /**
     * Get all registered patterns.
     */
    public Collection<BitmapPattern> getAllPatterns() {
        return Collections.unmodifiableCollection(patternRegistry.values());
    }

    // ========== Subsystem Accessors ==========

    public TransitionManager getTransitionManager() { return transitionManager; }
    public EffectsProcessor getEffectsProcessor() { return effectsProcessor; }
    public GameStateModulator getGameStateModulator() { return gameStateModulator; }

    // ========== Zone Management ==========

    /**
     * Activate a bitmap zone with a specific pattern and grid dimensions.
     */
    public void activateZone(String zoneName, String patternId, int width, int height) {
        BitmapPattern pattern = patternRegistry.get(patternId);
        if (pattern == null) {
            plugin.getLogger().warning("Unknown bitmap pattern: " + patternId +
                ". Using bmp_spectrum as default.");
            pattern = patternRegistry.get("bmp_spectrum");
            if (pattern == null) {
                plugin.getLogger().severe("No bitmap patterns registered!");
                return;
            }
        }

        BitmapFrameBuffer buffer = new BitmapFrameBuffer(width, height);
        zoneStates.put(zoneName.toLowerCase(), new ZoneState(pattern, buffer));
        asyncRenderer.registerZone(zoneName, width, height);

        // Auto-configure MinimapPattern center from zone origin
        if (pattern instanceof MinimapPattern minimap) {
            VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
            if (zone != null) {
                Location origin = zone.getOrigin();
                minimap.setCenter(origin.getX(), origin.getZ());
            }
        }

        plugin.getLogger().info("Bitmap zone '" + zoneName + "' activated with pattern '"
            + pattern.getId() + "' (" + width + "x" + height + ")");
    }

    /**
     * Change the active pattern for a bitmap zone (instant cut).
     */
    public void setPattern(String zoneName, String patternId) {
        setPattern(zoneName, patternId, null, 0);
    }

    /**
     * Change the active pattern with an optional transition.
     *
     * @param zoneName       zone key
     * @param patternId      new pattern ID
     * @param transitionId   transition type (null = instant cut)
     * @param durationTicks  transition duration in ticks (0 = instant cut)
     */
    public void setPattern(String zoneName, String patternId,
                           String transitionId, int durationTicks) {
        String key = zoneName.toLowerCase();
        ZoneState state = zoneStates.get(key);
        if (state == null) {
            plugin.getLogger().warning("Cannot set pattern: bitmap zone '" + zoneName + "' not active");
            return;
        }

        BitmapPattern newPattern = patternRegistry.get(patternId);
        if (newPattern == null) {
            plugin.getLogger().warning("Unknown bitmap pattern: " + patternId);
            return;
        }

        if (durationTicks > 0 && transitionId != null) {
            // Smooth transition via TransitionManager
            transitionManager.startTransition(key, state.pattern, newPattern,
                transitionId, durationTicks,
                state.buffer.getWidth(), state.buffer.getHeight());
            state.pendingPattern = newPattern;
        } else {
            // Instant cut — drain in-flight async render first
            asyncRenderer.drainZone(zoneName);
            state.pattern.reset();
            state.pattern = newPattern;
            newPattern.reset();
            state.buffer.clear();
        }

        plugin.getLogger().info("Bitmap zone '" + zoneName + "' "
            + (durationTicks > 0 ? "transitioning to" : "switched to")
            + " pattern '" + patternId + "'");
    }

    /**
     * Deactivate a bitmap zone.
     */
    public void deactivateZone(String zoneName) {
        String key = zoneName.toLowerCase();
        transitionManager.cancel(key);
        ZoneState state = zoneStates.remove(key);
        asyncRenderer.removeZone(zoneName);
        if (state != null) {
            state.pattern.reset();
        }
    }

    /**
     * Check if a zone has an active bitmap pattern.
     */
    public boolean isActive(String zoneName) {
        return zoneStates.containsKey(zoneName.toLowerCase());
    }

    /**
     * Get the active pattern ID for a zone.
     */
    public String getActivePatternId(String zoneName) {
        ZoneState state = zoneStates.get(zoneName.toLowerCase());
        return state != null ? state.pattern.getId() : null;
    }

    // ========== Render Loop ==========

    /**
     * Tick all active bitmap zones. Called from the main visualization tick.
     *
     * <p>Pipeline per zone:
     * <ol>
     *   <li>Pattern renders into buffer (or transition blends two patterns)</li>
     *   <li>Game state modulation (time of day, weather)</li>
     *   <li>Post-processing effects (strobe, freeze, palette, brightness)</li>
     *   <li>Push to renderer backend (dirty-checked entity updates)</li>
     * </ol>
     */
    public void tick(AudioState audio) {
        if (zoneStates.isEmpty()) return;

        broadcastTickCounter++;

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

                    // Broadcast frame to WebSocket clients for browser preview
                    if (broadcastTickCounter % BROADCAST_INTERVAL_TICKS == 0
                            && plugin.getWebSocketServer() != null
                            && plugin.getWebSocketServer().getConnectionCount() > 0) {
                        broadcastFrame(zoneName, state);
                    }
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

    private void broadcastFrame(String zoneName, ZoneState state) {
        try {
            BitmapFrameBuffer buffer = state.buffer;
            int[] pixels = buffer.getRawPixels();
            int pixelCount = buffer.getPixelCount();
            int width = buffer.getWidth();
            int height = buffer.getHeight();

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

                    plugin.getWebSocketServer().broadcast(msg);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to broadcast bitmap frame for zone '"
                        + zoneName + "': " + e.getMessage());
                }
            });
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to prepare bitmap frame broadcast for zone '"
                + zoneName + "': " + e.getMessage());
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
                plugin.getLogger().warning(msg + " [SLOW - exceeds 25ms budget]");
            } else {
                plugin.getLogger().info(msg);
            }
        }
    }

    /**
     * Clean up all zones and subsystems.
     */
    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        broadcastExecutor.shutdownNow();
        transitionManager.cancelAll();
        effectsProcessor.reset();
        asyncRenderer.shutdown();
        for (ZoneState state : zoneStates.values()) {
            state.pattern.reset();
        }
        zoneStates.clear();
    }

    /**
     * Get the frame buffer for external rendering (e.g., WebSocket bitmap_frame push).
     */
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
