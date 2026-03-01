package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.Random;

/**
 * Classic fire effect using bottom-up heat propagation.
 *
 * <p>Heat seeds are placed at the bottom row (intensity scales with bass).
 * Each tick propagates heat upward with random cooling. Color maps from
 * black → red → orange → yellow → white. Beat triggers a burst of extra heat.
 */
public class BitmapFire extends BitmapPattern {

    private double[] heatMap;
    private boolean initialized = false;
    private final Random rng = new Random(42);
    private double beatPulse = 0;

    // Fire palette: black → dark red → red → orange → yellow → white
    private static final int[] FIRE_PALETTE = buildFirePalette();

    public BitmapFire() {
        super("bmp_fire", "Bitmap Fire",
              "Rising flame effect with audio-reactive intensity");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        if (!initialized || heatMap == null || heatMap.length != w * h) {
            heatMap = new double[w * h];
            initialized = true;
        }

        double bass = audio.getBass();
        double amplitude = audio.getAmplitude();

        if (audio.isBeat()) {
            beatPulse = 1.0;
        } else {
            beatPulse *= 0.75;
        }

        // Seed bottom row with heat based on audio
        double heatIntensity = 0.4 + bass * 0.5 + beatPulse * 0.3;
        for (int x = 0; x < w; x++) {
            double seed = rng.nextDouble() * heatIntensity;
            // Beat burst: extra heat across entire bottom
            if (beatPulse > 0.3) {
                seed += beatPulse * 0.5 * rng.nextDouble();
            }
            heatMap[(h - 1) * w + x] = Math.min(1.0, seed);
            // Also seed second-to-bottom for thicker base
            if (h > 1) {
                heatMap[(h - 2) * w + x] = Math.min(1.0, heatMap[(h - 2) * w + x] + seed * 0.5);
            }
        }

        // Propagate heat upward: average neighbors below with cooling
        double coolingFactor = 0.06 - amplitude * 0.02; // Less cooling when louder
        coolingFactor = Math.max(0.02, coolingFactor);

        for (int y = 0; y < h - 1; y++) {
            for (int x = 0; x < w; x++) {
                double below = heatMap[(y + 1) * w + x];
                double left = (x > 0) ? heatMap[(y + 1) * w + x - 1] : below;
                double right = (x < w - 1) ? heatMap[(y + 1) * w + x + 1] : below;
                double belowBelow = (y + 2 < h) ? heatMap[(y + 2) * w + x] : below;

                double heat = (below + left + right + belowBelow) / 4.0;
                heat -= coolingFactor + rng.nextDouble() * 0.03;
                heatMap[y * w + x] = Math.max(0, heat);
            }
        }

        // Render heat map to pixels
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double heat = Math.min(1.0, heatMap[y * w + x]);
                int paletteIdx = (int) (heat * (FIRE_PALETTE.length - 1));
                buffer.setPixel(x, y, FIRE_PALETTE[paletteIdx]);
            }
        }
    }

    private static int[] buildFirePalette() {
        int[] palette = new int[256];
        for (int i = 0; i < 256; i++) {
            double t = i / 255.0;
            int r, g, b;
            if (t < 0.33) {
                // Black to dark red
                double lt = t / 0.33;
                r = (int) (lt * 200);
                g = 0;
                b = 0;
            } else if (t < 0.6) {
                // Dark red to orange
                double lt = (t - 0.33) / 0.27;
                r = 200 + (int) (lt * 55);
                g = (int) (lt * 160);
                b = 0;
            } else if (t < 0.85) {
                // Orange to yellow
                double lt = (t - 0.6) / 0.25;
                r = 255;
                g = 160 + (int) (lt * 95);
                b = (int) (lt * 30);
            } else {
                // Yellow to white
                double lt = (t - 0.85) / 0.15;
                r = 255;
                g = 255;
                b = 30 + (int) (lt * 225);
            }
            palette[i] = BitmapFrameBuffer.packARGB(255,
                Math.min(255, r), Math.min(255, g), Math.min(255, b));
        }
        return palette;
    }

    @Override
    public void reset() {
        heatMap = null;
        initialized = false;
        beatPulse = 0;
    }

    @Override
    public void onResize(int width, int height) {
        initialized = false;
    }
}
