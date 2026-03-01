package com.audioviz.bitmap.composition;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.bitmap.effects.ColorPalette;
import com.audioviz.bitmap.effects.EffectsProcessor;
import com.audioviz.bitmap.effects.LayerCompositor;
import com.audioviz.bitmap.gamestate.GameStateModulator;
import com.audioviz.bitmap.transitions.TransitionManager;
import com.audioviz.patterns.AudioState;

import java.util.*;

/**
 * Coordinates multiple bitmap zones into a unified visual experience.
 *
 * <p>Each zone runs its own pattern, but the composition manager can:
 * <ul>
 *   <li>Synchronize palette across all zones</li>
 *   <li>Flash all zones simultaneously on beat drops</li>
 *   <li>Mirror patterns between zones (L/R symmetry)</li>
 *   <li>Run layered patterns with blend modes per zone</li>
 *   <li>Manage transitions with coordinated timing</li>
 * </ul>
 *
 * <p>This sits between the pattern manager and renderer, intercepting
 * frames before they're pushed to TextDisplay entities.
 */
public class CompositionManager {

    /** Per-zone composition state. */
    private final Map<String, ZoneState> zones = new LinkedHashMap<>();

    /** Shared palette applied to all zones (null = per-zone palettes). */
    private ColorPalette sharedPalette = null;

    /** Shared effects processor for global effects. */
    private final EffectsProcessor globalEffects = new EffectsProcessor();

    /** Transition manager for coordinated pattern switches. */
    private final TransitionManager transitionManager = new TransitionManager();

    /** Game state modulator (optional). */
    private GameStateModulator gameStateModulator = null;

    /** Reusable output map (cleared each tick instead of reallocated). */
    private final Map<String, BitmapFrameBuffer> tickOutputs = new LinkedHashMap<>();

    /** Sync mode for multi-zone coordination. */
    private SyncMode syncMode = SyncMode.INDEPENDENT;

    /** Mirror source zone (for MIRROR mode). */
    private String mirrorSource = null;

    // ========== Zone Management ==========

    public ZoneState registerZone(String zoneName, int width, int height) {
        ZoneState state = new ZoneState(zoneName, width, height);
        zones.put(zoneName, state);
        return state;
    }

    public void removeZone(String zoneName) {
        zones.remove(zoneName);
    }

    public ZoneState getZone(String zoneName) {
        return zones.get(zoneName);
    }

    public Set<String> getZoneNames() {
        return Collections.unmodifiableSet(zones.keySet());
    }

    // ========== Composition Tick ==========

    public Map<String, BitmapFrameBuffer> tick(AudioState audio, double time) {
        tickOutputs.clear();
        Map<String, BitmapFrameBuffer> outputs = tickOutputs;

        if (gameStateModulator != null) {
            gameStateModulator.refreshWorldState();
        }

        for (Map.Entry<String, ZoneState> entry : zones.entrySet()) {
            String zoneName = entry.getKey();
            ZoneState zone = entry.getValue();

            BitmapFrameBuffer output = zone.outputBuffer;
            output.clear();

            // Handle mirror mode
            if (syncMode == SyncMode.MIRROR && mirrorSource != null
                    && !zoneName.equals(mirrorSource)) {
                ZoneState source = zones.get(mirrorSource);
                if (source != null) {
                    mirrorBuffer(source.outputBuffer, output);
                    outputs.put(zoneName, output);
                    continue;
                }
            }

            // Check for active transition
            boolean transitioning = transitionManager.isTransitioning(zoneName);
            if (transitioning) {
                boolean stillActive = transitionManager.tick(zoneName, output, audio, time);
                if (!stillActive && zone.primaryPattern != null) {
                    BitmapPattern incoming = transitionManager.getIncomingPattern(zoneName);
                    if (incoming != null) {
                        zone.primaryPattern = incoming;
                    }
                }
            } else if (zone.primaryPattern != null) {
                zone.primaryPattern.render(output, audio, time);
            }

            // Layer secondary pattern if set
            if (zone.secondaryPattern != null && zone.secondaryBuffer != null) {
                zone.secondaryBuffer.clear();
                zone.secondaryPattern.render(zone.secondaryBuffer, audio, time);
                LayerCompositor.blendInPlace(output, zone.secondaryBuffer,
                    zone.blendMode, zone.secondaryOpacity);
            }

            // Apply per-zone palette
            ColorPalette palette = (sharedPalette != null) ? sharedPalette : zone.palette;
            if (palette != null) {
                palette.applyToBuffer(output);
            }

            // Game state modulation
            if (gameStateModulator != null) {
                gameStateModulator.modulate(output, 0.05);
            }

            // Per-zone effects
            if (zone.effects != null) {
                zone.effects.process(output, audio, time);
            }

            // Global effects (applied to all zones)
            globalEffects.process(output, audio, time);

            outputs.put(zoneName, output);
        }

        return outputs;
    }

    // ========== Coordinated Operations ==========

    public void switchPattern(String zoneName, BitmapPattern newPattern,
                              String transitionId, int durationTicks) {
        ZoneState zone = zones.get(zoneName);
        if (zone == null || zone.primaryPattern == null) {
            if (zone != null) zone.primaryPattern = newPattern;
            return;
        }

        transitionManager.startTransition(zoneName, zone.primaryPattern, newPattern,
            transitionId, durationTicks, zone.width, zone.height);
    }

    public void switchAllPatterns(Map<String, BitmapPattern> newPatterns,
                                  String transitionId, int durationTicks) {
        for (Map.Entry<String, BitmapPattern> entry : newPatterns.entrySet()) {
            switchPattern(entry.getKey(), entry.getValue(), transitionId, durationTicks);
        }
    }

    public void flashAll(int color, double intensity) {
        for (ZoneState zone : zones.values()) {
            if (zone.effects == null) {
                zone.effects = new EffectsProcessor();
            }
            zone.effects.setWash(color, intensity);
        }
    }

    public void setSharedPalette(ColorPalette palette) {
        this.sharedPalette = palette;
    }

    public void clearSharedPalette() {
        this.sharedPalette = null;
    }

    // ========== Sync Modes ==========

    public void setSyncMode(SyncMode mode) { this.syncMode = mode; }
    public SyncMode getSyncMode() { return syncMode; }

    public void setMirrorSource(String zoneName) { this.mirrorSource = zoneName; }

    // ========== Game State ==========

    public void setGameStateModulator(GameStateModulator modulator) {
        this.gameStateModulator = modulator;
    }

    // ========== Global Effects ==========

    public EffectsProcessor getGlobalEffects() { return globalEffects; }
    public TransitionManager getTransitionManager() { return transitionManager; }

    // ========== Helpers ==========

    private static void mirrorBuffer(BitmapFrameBuffer source, BitmapFrameBuffer output) {
        int w = Math.min(source.getWidth(), output.getWidth());
        int h = Math.min(source.getHeight(), output.getHeight());
        int[] src = source.getRawPixels();
        int[] dst = output.getRawPixels();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst[y * output.getWidth() + x] = src[y * source.getWidth() + (w - 1 - x)];
            }
        }
    }

    public void shutdown() {
        transitionManager.cancelAll();
        globalEffects.reset();
        zones.clear();
    }

    // ========== Inner Classes ==========

    public enum SyncMode {
        INDEPENDENT,
        MIRROR,
        UNIFIED
    }

    public static class ZoneState {
        public final String name;
        public final int width;
        public final int height;
        public final BitmapFrameBuffer outputBuffer;

        public BitmapPattern primaryPattern;
        public BitmapPattern secondaryPattern;
        public BitmapFrameBuffer secondaryBuffer;
        public LayerCompositor.BlendMode blendMode = LayerCompositor.BlendMode.ADDITIVE;
        public double secondaryOpacity = 0.5;
        public ColorPalette palette;
        public EffectsProcessor effects;

        ZoneState(String name, int width, int height) {
            this.name = name;
            this.width = width;
            this.height = height;
            this.outputBuffer = new BitmapFrameBuffer(width, height);
        }

        public void setSecondaryLayer(BitmapPattern pattern, LayerCompositor.BlendMode mode,
                                       double opacity) {
            this.secondaryPattern = pattern;
            this.secondaryBuffer = new BitmapFrameBuffer(width, height);
            this.blendMode = mode;
            this.secondaryOpacity = opacity;
        }

        public void clearSecondaryLayer() {
            this.secondaryPattern = null;
            this.secondaryBuffer = null;
        }
    }
}
