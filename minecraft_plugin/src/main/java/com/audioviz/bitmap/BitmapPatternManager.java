package com.audioviz.bitmap;

import com.audioviz.AudioVizPlugin;
import com.audioviz.bitmap.effects.EffectsProcessor;
import com.audioviz.bitmap.gamestate.*;
import com.audioviz.bitmap.media.ImagePattern;
import com.audioviz.bitmap.patterns.*;
import com.audioviz.bitmap.text.*;
import com.audioviz.bitmap.transitions.TransitionManager;
import com.audioviz.patterns.AudioState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    /** All registered bitmap patterns, keyed by pattern ID. */
    private final Map<String, BitmapPattern> patternRegistry = new LinkedHashMap<>();

    /** Per-zone state: active pattern + frame buffer. */
    private final Map<String, ZoneState> zoneStates = new ConcurrentHashMap<>();

    /** Time tracking for pattern animation. */
    private final long startTimeMs = System.currentTimeMillis();
    private long lastTickMs = System.currentTimeMillis();

    /** Transition engine (shared across zones). */
    private final TransitionManager transitionManager = new TransitionManager();

    /** Post-processing effects (shared across zones, applied globally). */
    private final EffectsProcessor effectsProcessor = new EffectsProcessor();

    /** Game state modulator (Bukkit-dependent). */
    private final GameStateModulator gameStateModulator;

    public BitmapPatternManager(AudioVizPlugin plugin, BitmapRendererBackend renderer) {
        this.plugin = plugin;
        this.renderer = renderer;
        this.gameStateModulator = new GameStateModulator(plugin);
        registerBuiltInPatterns();
    }

    // ========== Pattern Registry ==========

    private void registerBuiltInPatterns() {
        // Core audio-reactive patterns (Phase 1)
        register(new BitmapSpectrumBars());
        register(new BitmapSpectrogram());
        register(new BitmapPlasma());
        register(new BitmapWaveform());
        register(new BitmapVUMeter());

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
            // Instant cut
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

        long now = System.currentTimeMillis();
        double time = (now - startTimeMs) / 1000.0;
        double dt = (now - lastTickMs) / 1000.0;
        lastTickMs = now;

        // Refresh game state (throttled internally to every ~1s)
        gameStateModulator.refreshWorldState();

        for (Map.Entry<String, ZoneState> entry : zoneStates.entrySet()) {
            String zoneName = entry.getKey();
            ZoneState state = entry.getValue();

            try {
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

                // Step 2: Game state modulation (time of day, weather tinting)
                gameStateModulator.modulate(state.buffer, dt);

                // Step 3: Post-processing effects (strobe, freeze, palette, brightness)
                effectsProcessor.process(state.buffer, audio, time);

                // Step 4: Push to renderer backend (dirty-checked)
                renderer.applyFrame(zoneName, state.buffer);
            } catch (Exception e) {
                plugin.getLogger().warning("Error in bitmap zone '" + zoneName
                    + "' pattern '" + state.pattern.getId() + "': " + e.getMessage());
            }
        }
    }

    /**
     * Clean up all zones and subsystems.
     */
    public void shutdown() {
        transitionManager.cancelAll();
        effectsProcessor.reset();
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
        BitmapPattern pendingPattern; // Non-null during transitions
        final BitmapFrameBuffer buffer;

        ZoneState(BitmapPattern pattern, BitmapFrameBuffer buffer) {
            this.pattern = pattern;
            this.buffer = buffer;
        }
    }
}
