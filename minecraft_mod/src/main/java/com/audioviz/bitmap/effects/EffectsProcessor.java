package com.audioviz.bitmap.effects;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.patterns.AudioState;

/**
 * Post-processing effects chain applied after pattern rendering.
 *
 * <p>Effects are applied in order: pattern → strobe → freeze → palette →
 * brightness → output. Each effect can be independently enabled/disabled
 * by the VJ server in real-time.
 */
public class EffectsProcessor {

    // ========== Strobe ==========
    private boolean strobeEnabled = false;
    private int strobeBeatDivisor = 1;
    private int strobeBeatCount = 0;
    private double strobeDecay = 0;
    private int strobeColor = 0xFFFFFFFF;

    // ========== Freeze Frame ==========
    private boolean freezeEnabled = false;
    private int[] frozenFrame = null;

    // ========== Palette ==========
    private ColorPalette activePalette = null;
    private boolean paletteEnabled = false;

    // ========== Global Brightness ==========
    private double brightness = 1.0;

    // ========== Color Wash ==========
    private int washColor = 0;
    private double washOpacity = 0;

    // ========== Beat Flash ==========
    private boolean beatFlashEnabled = false;
    private double beatFlashIntensity = 0;

    // ========== RGB Split ==========
    private boolean rgbSplitEnabled = false;
    private int rgbSplitOffset = 2;
    private double rgbSplitBeatDecay = 0;

    // ========== Bit Crush ==========
    private boolean bitCrushEnabled = false;
    private int bitCrushColorLevels = 8;
    private int bitCrushPixelSize = 1;

    // ========== Edge Flash ==========
    private boolean edgeFlashEnabled = false;
    private double edgeFlashIntensity = 0;
    private int edgeFlashColor = 0xFFFFFFFF;
    private int edgeFlashWidth = 2;

    public void process(BitmapFrameBuffer buffer, AudioState audio, double time) {
        if (audio != null && audio.isBeat()) {
            strobeBeatCount++;
            if (beatFlashEnabled) {
                beatFlashIntensity = 0.3;
            }
            if (rgbSplitEnabled) {
                rgbSplitBeatDecay = 1.0;
            }
            if (edgeFlashEnabled) {
                edgeFlashIntensity = audio.getBeatIntensity();
            }
        }
        rgbSplitBeatDecay *= 0.85;
        edgeFlashIntensity *= 0.8;

        if (freezeEnabled && frozenFrame != null) {
            int[] pixels = buffer.getRawPixels();
            System.arraycopy(frozenFrame, 0, pixels, 0,
                Math.min(pixels.length, frozenFrame.length));
            return;
        }

        if (paletteEnabled && activePalette != null) {
            activePalette.applyToBuffer(buffer);
        }

        if (strobeEnabled && strobeBeatCount % strobeBeatDivisor == 0) {
            strobeDecay = 1.0;
        }
        if (strobeDecay > 0.01) {
            applyFlash(buffer, strobeColor, strobeDecay);
            strobeDecay *= 0.7;
        }

        if (beatFlashIntensity > 0.01) {
            applyFlash(buffer, 0xFFFFFFFF, beatFlashIntensity * 0.2);
            beatFlashIntensity *= 0.85;
        }

        if (rgbSplitEnabled) {
            int totalOffset = rgbSplitOffset + (int) (rgbSplitBeatDecay * 3);
            if (totalOffset > 0) {
                applyRgbSplit(buffer, totalOffset);
            }
        }

        if (bitCrushEnabled) {
            applyBitCrush(buffer, bitCrushColorLevels, bitCrushPixelSize);
        }

        if (washOpacity > 0.01) {
            applyWash(buffer, washColor, washOpacity);
        }

        if (edgeFlashEnabled && edgeFlashIntensity > 0.02) {
            applyEdgeFlash(buffer, edgeFlashColor, edgeFlashIntensity, edgeFlashWidth);
        }

        if (brightness < 0.99) {
            applyBrightness(buffer, brightness);
        }
    }

    // ========== Controls ==========

    public void setStrobeEnabled(boolean enabled) { this.strobeEnabled = enabled; }
    public boolean isStrobeEnabled() { return strobeEnabled; }
    public void setStrobeDivisor(int everyNthBeat) { this.strobeBeatDivisor = Math.max(1, everyNthBeat); }
    public void setStrobeColor(int argb) { this.strobeColor = argb; }

    public void freeze(BitmapFrameBuffer currentBuffer) {
        frozenFrame = currentBuffer.getRawPixels().clone();
        freezeEnabled = true;
    }
    public void unfreeze() { freezeEnabled = false; frozenFrame = null; }
    public boolean isFrozen() { return freezeEnabled; }

    public void setPalette(ColorPalette palette) {
        this.activePalette = palette;
        this.paletteEnabled = (palette != null);
    }
    public void clearPalette() { activePalette = null; paletteEnabled = false; }
    public ColorPalette getActivePalette() { return activePalette; }
    public boolean isPaletteEnabled() { return paletteEnabled; }

    public void setBrightness(double level) { this.brightness = Math.max(0, Math.min(1, level)); }
    public double getBrightness() { return brightness; }
    public void blackout(boolean on) { this.brightness = on ? 0.0 : 1.0; }

    public void setWash(int color, double opacity) {
        this.washColor = color;
        this.washOpacity = Math.max(0, Math.min(1, opacity));
    }
    public void clearWash() { this.washOpacity = 0; }

    public void setBeatFlashEnabled(boolean enabled) { this.beatFlashEnabled = enabled; }
    public boolean isBeatFlashEnabled() { return beatFlashEnabled; }

    public void setRgbSplitEnabled(boolean enabled) { this.rgbSplitEnabled = enabled; }
    public boolean isRgbSplitEnabled() { return rgbSplitEnabled; }
    public void setRgbSplitOffset(int pixels) { this.rgbSplitOffset = Math.max(0, Math.min(10, pixels)); }

    public void setBitCrushEnabled(boolean enabled) { this.bitCrushEnabled = enabled; }
    public boolean isBitCrushEnabled() { return bitCrushEnabled; }
    public void setBitCrushColorLevels(int levels) { this.bitCrushColorLevels = Math.max(2, Math.min(256, levels)); }
    public void setBitCrushPixelSize(int size) { this.bitCrushPixelSize = Math.max(1, Math.min(8, size)); }

    public void setEdgeFlashEnabled(boolean enabled) { this.edgeFlashEnabled = enabled; }
    public boolean isEdgeFlashEnabled() { return edgeFlashEnabled; }
    public void setEdgeFlashColor(int argb) { this.edgeFlashColor = argb; }
    public void setEdgeFlashWidth(int pixels) { this.edgeFlashWidth = Math.max(1, Math.min(10, pixels)); }

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

    private static void applyRgbSplit(BitmapFrameBuffer buffer, int offset) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        int[] pixels = buffer.getRawPixels();
        int[] snapshot = pixels.clone();

        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int center = snapshot[row + x];
                int redSrcX = Math.max(0, Math.min(w - 1, x - offset));
                int blueSrcX = Math.max(0, Math.min(w - 1, x + offset));
                int redSrc = snapshot[row + redSrcX];
                int blueSrc = snapshot[row + blueSrcX];

                int a = (center >> 24) & 0xFF;
                int r = (redSrc >> 16) & 0xFF;
                int g = (center >> 8) & 0xFF;
                int b = blueSrc & 0xFF;
                pixels[row + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
    }

    private static void applyBitCrush(BitmapFrameBuffer buffer, int colorLevels, int pixelSize) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        int[] pixels = buffer.getRawPixels();

        double step = 255.0 / (colorLevels - 1);

        if (pixelSize > 1) {
            for (int by = 0; by < h; by += pixelSize) {
                for (int bx = 0; bx < w; bx += pixelSize) {
                    int leader = pixels[by * w + bx];
                    for (int dy = 0; dy < pixelSize && by + dy < h; dy++) {
                        for (int dx = 0; dx < pixelSize && bx + dx < w; dx++) {
                            pixels[(by + dy) * w + (bx + dx)] = leader;
                        }
                    }
                }
            }
        }

        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int a = (c >> 24) & 0xFF;
            int r = (int) (Math.round(((c >> 16) & 0xFF) / step) * step);
            int g = (int) (Math.round(((c >> 8) & 0xFF) / step) * step);
            int b = (int) (Math.round((c & 0xFF) / step) * step);
            pixels[i] = (a << 24)
                | (Math.min(255, r) << 16)
                | (Math.min(255, g) << 8)
                | Math.min(255, b);
        }
    }

    private static void applyEdgeFlash(BitmapFrameBuffer buffer, int flashColor,
                                        double intensity, int borderWidth) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        int[] pixels = buffer.getRawPixels();
        float maxT = (float) Math.min(1.0, intensity);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int distFromEdge = Math.min(Math.min(x, w - 1 - x), Math.min(y, h - 1 - y));
                if (distFromEdge < borderWidth) {
                    float t = maxT * (1.0f - (float) distFromEdge / borderWidth);
                    int idx = y * w + x;
                    pixels[idx] = BitmapFrameBuffer.lerpColor(pixels[idx], flashColor, t);
                }
            }
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
        rgbSplitEnabled = false;
        rgbSplitBeatDecay = 0;
        bitCrushEnabled = false;
        bitCrushColorLevels = 8;
        bitCrushPixelSize = 1;
        edgeFlashEnabled = false;
        edgeFlashIntensity = 0;
    }
}
