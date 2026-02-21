package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

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
 * <p>This is the #1 requested LED wall pattern at music festivals — it turns
 * the audio into a flowing river of color.
 */
public class BitmapSpectrogram extends BitmapPattern {

    /** History buffer: each column is one time slice of frequency data. */
    private double[][] history; // [timeSlot][frequencyBin]
    private int writeHead = 0;
    private boolean initialized = false;

    public BitmapSpectrogram() {
        super("bmp_spectrogram", "Bitmap Spectrogram",
              "Scrolling frequency × time waterfall heatmap");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        // Lazy init history buffer
        if (!initialized || history == null || history.length != w) {
            history = new double[w][h];
            writeHead = 0;
            initialized = true;
        }

        // Write current audio frame into the newest column
        double[] bands = audio.getBands();
        double[] column = new double[h];

        // Interpolate 5 bands across the full height
        for (int row = 0; row < h; row++) {
            // Map row to frequency: row 0 = top (high freq), row h-1 = bottom (bass)
            double freqNorm = 1.0 - (double) row / (h - 1); // 0 = bass, 1 = highs
            double bandFloat = freqNorm * (bands.length - 1);
            int bandLow = Math.min((int) bandFloat, bands.length - 2);
            int bandHigh = bandLow + 1;
            double frac = bandFloat - bandLow;

            double value = bands[bandLow] * (1 - frac) + bands[bandHigh] * frac;

            // Beat boost for bass region
            if (audio.isBeat() && freqNorm < 0.3) {
                value = Math.min(1.0, value + 0.4 * audio.getBeatIntensity());
            }

            column[row] = value;
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

                // Apply slight gamma for punchier visuals
                intensity = Math.pow(intensity, 0.7);

                int color = BitmapFrameBuffer.heatMapColor(intensity);
                buffer.setPixel(col, row, color);
            }
        }
    }

    @Override
    public void reset() {
        history = null;
        writeHead = 0;
        initialized = false;
    }

    @Override
    public void onResize(int width, int height) {
        // Force re-init on next render
        initialized = false;
    }
}
