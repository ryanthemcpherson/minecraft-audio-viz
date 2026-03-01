package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.Random;

/**
 * Classic fire effect using bottom-up heat propagation.
 *
 * <p>Heat seeds are placed at the bottom rows (intensity scales with bass).
 * Each tick propagates heat upward multiple steps (scaled to display height)
 * with random cooling. Color maps from black → red → orange → yellow → white.
 * Beat triggers a burst of extra heat.
 *
 * <p>Designed for high-res (768x768+) — propagation steps scale with height
 * so fire fills a consistent portion of the display regardless of resolution.
 */
public class BitmapFire extends BitmapPattern {

    private double[] heatMap;
    private boolean initialized = false;
    private final Random rng = new Random(42);
    private double beatPulse = 0;
    private double beatGlow = 0;

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
            beatGlow = 1.0;
        } else {
            beatPulse *= 0.75;
            beatGlow *= 0.92;
        }

        // Scale propagation steps to height so fire fills ~40-60% of display
        // At 128px: 1 step. At 768px: ~6 steps per tick.
        int propSteps = Math.max(1, h / 128);

        for (int step = 0; step < propSteps; step++) {
            // Seed bottom rows with heat based on audio
            double heatIntensity = 0.5 + bass * 0.4 + beatPulse * 0.4;
            int seedRows = Math.max(2, h / 64); // thicker base on large displays
            for (int x = 0; x < w; x++) {
                for (int row = 0; row < seedRows; row++) {
                    int y = h - 1 - row;
                    double falloff = 1.0 - (double) row / seedRows;
                    double seed = rng.nextDouble() * heatIntensity * falloff;
                    if (beatPulse > 0.3) {
                        seed += beatPulse * 0.4 * rng.nextDouble() * falloff;
                    }
                    heatMap[y * w + x] = Math.min(1.0, heatMap[y * w + x] + seed);
                }
            }

            // Propagate heat upward with cooling
            double coolingFactor = 0.04 - amplitude * 0.015;
            coolingFactor = Math.max(0.015, coolingFactor);
            // Less cooling per step when doing multiple steps
            double stepCooling = coolingFactor / Math.sqrt(propSteps);

            for (int y = 0; y < h - 1; y++) {
                for (int x = 0; x < w; x++) {
                    double below = heatMap[(y + 1) * w + x];
                    double left = (x > 0) ? heatMap[(y + 1) * w + x - 1] : below;
                    double right = (x < w - 1) ? heatMap[(y + 1) * w + x + 1] : below;
                    double belowBelow = (y + 2 < h) ? heatMap[(y + 2) * w + x] : below;

                    double left2 = (x > 1) ? heatMap[(y + 1) * w + x - 2] : left;
                    double right2 = (x < w - 2) ? heatMap[(y + 1) * w + x + 2] : right;

                    double heat = (below * 3 + left + right + belowBelow + left2 * 0.5 + right2 * 0.5) / 7.0;
                    heat -= stepCooling + rng.nextDouble() * 0.015;
                    heatMap[y * w + x] = Math.max(0, heat);
                }
            }
        }

        // Render heat map to pixels
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double heat = Math.min(1.0, heatMap[y * w + x]);
                // Add subtle beat glow to existing flames
                if (beatGlow > 0.1 && heat > 0.05) {
                    heat = Math.min(1.0, heat + beatGlow * 0.15);
                }
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
            if (t < 0.25) {
                // Black to dark red (deep embers)
                double lt = t / 0.25;
                r = (int) (lt * 180);
                g = 0;
                b = (int) (lt * 15); // tiny blue tinge in deep heat
            } else if (t < 0.5) {
                // Dark red to bright red-orange
                double lt = (t - 0.25) / 0.25;
                r = 180 + (int) (lt * 75);
                g = (int) (lt * 100);
                b = (int) ((1 - lt) * 15);
            } else if (t < 0.7) {
                // Red-orange to orange-yellow
                double lt = (t - 0.5) / 0.2;
                r = 255;
                g = 100 + (int) (lt * 155);
                b = 0;
            } else if (t < 0.88) {
                // Yellow
                double lt = (t - 0.7) / 0.18;
                r = 255;
                g = 255;
                b = (int) (lt * 60);
            } else {
                // Yellow to bright white
                double lt = (t - 0.88) / 0.12;
                r = 255;
                g = 255;
                b = 60 + (int) (lt * 195);
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
        beatGlow = 0;
    }

    @Override
    public void onResize(int width, int height) {
        initialized = false;
    }
}
