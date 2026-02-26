package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.Random;

/**
 * Scrolling spectrogram (waterfall) display.
 *
 * <p>Each column represents a moment in time, scrolling left-to-right.
 * The vertical axis maps to frequency bands (bass at bottom, highs at top).
 * Pixel brightness/color represents energy at that frequency at that moment.
 *
 * <p>Uses the heat-map color scheme: quiet = deep blue, loud = bright white.
 * Beat hits create a flash column effect.
 *
 * <p>Designed for high-res (768x768+): sub-band detail is synthesized from
 * the 5-band input using harmonic structure, so the waterfall shows texture
 * rather than flat gradient bands.
 */
public class BitmapSpectrogram extends BitmapPattern {

    /** History buffer: each column is one time slice of frequency data. */
    private double[][] history; // [timeSlot][frequencyBin]
    private int writeHead = 0;
    private boolean initialized = false;
    private final Random rng = new Random(42);

    /** Cached noise table for sub-band texture (regenerated per column). */
    private double[] noiseRow;

    public BitmapSpectrogram() {
        super("bmp_spectrogram", "Bitmap Spectrogram",
              "Scrolling frequency x time waterfall heatmap");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        // Lazy init history buffer
        if (!initialized || history == null || history.length != w || history[0].length != h) {
            history = new double[w][h];
            noiseRow = new double[h];
            writeHead = 0;
            initialized = true;
        }

        // Write current audio frame into the newest column
        double[] bands = audio.getBands();
        double[] column = new double[h];

        // Generate per-row noise for sub-band texture
        // Uses smoothed noise so adjacent rows correlate (looks like real frequency detail)
        double noise = 0;
        for (int row = 0; row < h; row++) {
            noise = noise * 0.85 + (rng.nextDouble() - 0.5) * 0.3;
            noiseRow[row] = noise;
        }

        for (int row = 0; row < h; row++) {
            // Map row to frequency: row 0 = top (high freq), row h-1 = bottom (bass)
            double freqNorm = 1.0 - (double) row / (h - 1); // 0 = bass, 1 = highs
            double bandFloat = freqNorm * (bands.length - 1);
            int bandLow = Math.min((int) bandFloat, bands.length - 2);
            int bandHigh = bandLow + 1;
            double frac = bandFloat - bandLow;

            // Cubic-ish interpolation: use smoothstep for less linear banding
            frac = frac * frac * (3.0 - 2.0 * frac);
            double value = bands[bandLow] * (1 - frac) + bands[bandHigh] * frac;

            // Add sub-band texture: modulate based on noise and energy
            // More texture when the band has energy (quiet = smooth dark, loud = textured)
            double textureAmount = value * 0.25;
            value += noiseRow[row] * textureAmount;

            // Add harmonic ridges: slight peaks at musical frequency ratios
            double ridgePhase = freqNorm * h * 0.1;
            double ridge = Math.sin(ridgePhase) * 0.08 * value;
            value += ridge;

            // Beat boost for bass region
            if (audio.isBeat() && freqNorm < 0.3) {
                value = Math.min(1.0, value + 0.4 * audio.getBeatIntensity());
            }

            column[row] = Math.max(0, Math.min(1.0, value));
        }

        history[writeHead] = column;
        writeHead = (writeHead + 1) % w;

        // Render: read history from oldest to newest, left to right
        for (int col = 0; col < w; col++) {
            int histIdx = (writeHead + col) % w;
            double[] slice = history[histIdx];
            if (slice == null) continue;

            for (int row = 0; row < h; row++) {
                double intensity = Math.min(1.0, slice[row]);

                // Apply gamma for punchier visuals
                intensity = Math.pow(intensity, 0.65);

                int color = spectrogramColor(intensity);
                buffer.setPixel(col, row, color);
            }
        }
    }

    /**
     * Spectrogram-specific color map: dark blue → cyan → green → yellow → white.
     * More stops than generic heatMapColor for a richer waterfall look.
     */
    private static int spectrogramColor(double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        int r, g, b;
        if (t < 0.15) {
            // Black to deep blue
            double lt = t / 0.15;
            r = 0;
            g = 0;
            b = (int) (lt * 120);
        } else if (t < 0.3) {
            // Deep blue to cyan-blue
            double lt = (t - 0.15) / 0.15;
            r = 0;
            g = (int) (lt * 160);
            b = 120 + (int) (lt * 135);
        } else if (t < 0.5) {
            // Cyan to green
            double lt = (t - 0.3) / 0.2;
            r = 0;
            g = 160 + (int) (lt * 95);
            b = (int) ((1 - lt) * 255);
        } else if (t < 0.7) {
            // Green to yellow
            double lt = (t - 0.5) / 0.2;
            r = (int) (lt * 255);
            g = 255;
            b = 0;
        } else if (t < 0.88) {
            // Yellow to orange-red
            double lt = (t - 0.7) / 0.18;
            r = 255;
            g = (int) ((1 - lt * 0.5) * 255);
            b = 0;
        } else {
            // Orange-red to white (hottest)
            double lt = (t - 0.88) / 0.12;
            r = 255;
            g = (int) (128 + lt * 127);
            b = (int) (lt * 255);
        }
        return BitmapFrameBuffer.packARGB(255,
            Math.min(255, Math.max(0, r)),
            Math.min(255, Math.max(0, g)),
            Math.min(255, Math.max(0, b)));
    }

    @Override
    public void reset() {
        history = null;
        writeHead = 0;
        initialized = false;
    }

    @Override
    public void onResize(int width, int height) {
        initialized = false;
    }
}
