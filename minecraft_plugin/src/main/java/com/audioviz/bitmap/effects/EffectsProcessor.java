package com.audioviz.bitmap.effects;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.patterns.AudioState;

import java.util.*;

/**
 * Post-processing effects chain applied after pattern rendering.
 *
 * <p>Effects are applied in order: pattern → strobe → freeze → palette →
 * brightness → output. Each effect can be independently enabled/disabled
 * by the VJ server in real-time.
 *
 * <p>This is the core VJ performance toolkit — strobe, freeze frame,
 * palette remapping, global brightness, and color wash.
 */
public class EffectsProcessor {

    // ========== Strobe ==========
    private boolean strobeEnabled = false;
    private int strobeBeatDivisor = 1;    // Flash every Nth beat
    private int strobeBeatCount = 0;
    private double strobeDecay = 0;       // Current flash brightness
    private int strobeColor = 0xFFFFFFFF; // White flash

    // ========== Freeze Frame ==========
    private boolean freezeEnabled = false;
    private int[] frozenFrame = null;

    // ========== Palette ==========
    private ColorPalette activePalette = null;
    private boolean paletteEnabled = false;

    // ========== Global Brightness ==========
    private double brightness = 1.0; // 0.0 = blackout, 1.0 = full

    // ========== Color Wash ==========
    private int washColor = 0;
    private double washOpacity = 0;

    // ========== Beat Flash ==========
    private boolean beatFlashEnabled = false;
    private double beatFlashIntensity = 0;

    /**
     * Process a rendered frame buffer with all active effects.
     * Modifies the buffer in-place.
     */
    public void process(BitmapFrameBuffer buffer, AudioState audio, double time) {
        // Beat tracking
        if (audio != null && audio.isBeat()) {
            strobeBeatCount++;
            if (beatFlashEnabled) {
                beatFlashIntensity = 0.3;
            }
        }

        // 1. Freeze frame: replace buffer with frozen snapshot
        if (freezeEnabled && frozenFrame != null) {
            int[] pixels = buffer.getRawPixels();
            System.arraycopy(frozenFrame, 0, pixels, 0,
                Math.min(pixels.length, frozenFrame.length));
            return; // No further processing on frozen frame
        }

        // 2. Palette remap
        if (paletteEnabled && activePalette != null) {
            activePalette.applyToBuffer(buffer);
        }

        // 3. Beat strobe
        if (strobeEnabled && strobeBeatCount % strobeBeatDivisor == 0) {
            strobeDecay = 1.0;
        }
        if (strobeDecay > 0.01) {
            applyFlash(buffer, strobeColor, strobeDecay);
            strobeDecay *= 0.7; // Quick decay
        }

        // 4. Beat flash (subtle ambient pulse)
        if (beatFlashIntensity > 0.01) {
            applyFlash(buffer, 0xFFFFFFFF, beatFlashIntensity * 0.2);
            beatFlashIntensity *= 0.85;
        }

        // 5. Color wash overlay
        if (washOpacity > 0.01) {
            applyWash(buffer, washColor, washOpacity);
        }

        // 6. Global brightness (last — affects everything)
        if (brightness < 0.99) {
            applyBrightness(buffer, brightness);
        }
    }

    // ========== Strobe Controls ==========

    public void setStrobeEnabled(boolean enabled) { this.strobeEnabled = enabled; }
    public boolean isStrobeEnabled() { return strobeEnabled; }

    public void setStrobeDivisor(int everyNthBeat) {
        this.strobeBeatDivisor = Math.max(1, everyNthBeat);
    }

    public void setStrobeColor(int argb) { this.strobeColor = argb; }

    // ========== Freeze Controls ==========

    /**
     * Freeze the current frame.
     */
    public void freeze(BitmapFrameBuffer currentBuffer) {
        frozenFrame = currentBuffer.getRawPixels().clone();
        freezeEnabled = true;
    }

    /**
     * Unfreeze — resume live rendering.
     */
    public void unfreeze() {
        freezeEnabled = false;
        frozenFrame = null;
    }

    public boolean isFrozen() { return freezeEnabled; }

    // ========== Palette Controls ==========

    public void setPalette(ColorPalette palette) {
        this.activePalette = palette;
        this.paletteEnabled = (palette != null);
    }

    public void clearPalette() {
        this.activePalette = null;
        this.paletteEnabled = false;
    }

    public ColorPalette getActivePalette() { return activePalette; }
    public boolean isPaletteEnabled() { return paletteEnabled; }

    // ========== Brightness Controls ==========

    public void setBrightness(double level) {
        this.brightness = Math.max(0, Math.min(1, level));
    }

    public double getBrightness() { return brightness; }

    /**
     * Instant blackout toggle.
     */
    public void blackout(boolean on) {
        this.brightness = on ? 0.0 : 1.0;
    }

    // ========== Wash Controls ==========

    public void setWash(int color, double opacity) {
        this.washColor = color;
        this.washOpacity = Math.max(0, Math.min(1, opacity));
    }

    public void clearWash() { this.washOpacity = 0; }

    // ========== Beat Flash ==========

    public void setBeatFlashEnabled(boolean enabled) { this.beatFlashEnabled = enabled; }

    // ========== Effect Implementations ==========

    private static void applyFlash(BitmapFrameBuffer buffer, int flashColor, double intensity) {
        float t = (float) Math.max(0, Math.min(1, intensity));
        int[] pixels = buffer.getRawPixels();
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = BitmapFrameBuffer.lerpColor(pixels[i], flashColor, t);
        }
    }

    private static void applyWash(BitmapFrameBuffer buffer, int washColor, double opacity) {
        float t = (float) Math.max(0, Math.min(1, opacity));
        int[] pixels = buffer.getRawPixels();
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = BitmapFrameBuffer.lerpColor(pixels[i], washColor, t);
        }
    }

    private static void applyBrightness(BitmapFrameBuffer buffer, double level) {
        float f = (float) level;
        int[] pixels = buffer.getRawPixels();
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int a = (c >> 24) & 0xFF;
            int r = (int) (((c >> 16) & 0xFF) * f);
            int g = (int) (((c >> 8) & 0xFF) * f);
            int b = (int) ((c & 0xFF) * f);
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    /**
     * Reset all effects to default state.
     */
    public void reset() {
        strobeEnabled = false;
        strobeBeatCount = 0;
        strobeDecay = 0;
        freezeEnabled = false;
        frozenFrame = null;
        activePalette = null;
        paletteEnabled = false;
        brightness = 1.0;
        washColor = 0;
        washOpacity = 0;
        beatFlashEnabled = false;
        beatFlashIntensity = 0;
    }
}
