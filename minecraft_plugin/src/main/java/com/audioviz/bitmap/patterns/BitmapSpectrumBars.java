package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Classic LED wall spectrum analyzer.
 *
 * <p>Each column maps to a frequency band. Bar height follows band energy.
 * Color gradient: green at bottom → yellow → red at top (classic VU meter).
 * Beat flashes add a brightness pulse across all bars.
 *
 * <p>This is the bitmap equivalent of the existing 3D SpectrumBars pattern,
 * but rendered flat on the LED wall.
 */
public class BitmapSpectrumBars extends BitmapPattern {

    /** Smoothed band values for buttery animation. */
    private final double[] smoothBands = new double[5];

    /** Peak hold positions (0-1) per column group. */
    private final double[] peaks = new double[5];

    /** Frames since each peak was set (for decay). */
    private final int[] peakHold = new int[5];

    private static final int PEAK_HOLD_FRAMES = 15; // Hold peak for ~750ms
    private static final double PEAK_DECAY_RATE = 0.03;
    private static final double SMOOTH_FACTOR = 0.35; // 0 = instant, 1 = frozen

    // VU meter gradient colors (bottom to top)
    private static final int COLOR_GREEN  = BitmapFrameBuffer.rgb(0, 255, 60);
    private static final int COLOR_YELLOW = BitmapFrameBuffer.rgb(255, 255, 0);
    private static final int COLOR_RED    = BitmapFrameBuffer.rgb(255, 30, 0);
    private static final int COLOR_PEAK   = BitmapFrameBuffer.rgb(255, 255, 255);
    private static final int COLOR_BG     = BitmapFrameBuffer.packARGB(255, 5, 5, 15);

    public BitmapSpectrumBars() {
        super("bmp_spectrum", "Bitmap Spectrum Bars", "Classic LED wall spectrum analyzer with peak hold");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        // Clear to dark background
        buffer.fill(COLOR_BG);

        // Smooth band values
        double[] bands = audio.getBands();
        for (int i = 0; i < Math.min(5, bands.length); i++) {
            smoothBands[i] = smoothBands[i] * SMOOTH_FACTOR + bands[i] * (1 - SMOOTH_FACTOR);
        }

        // Beat brightness boost
        double beatBoost = audio.isBeat() ? 0.3 : 0.0;

        // Calculate bar width: divide screen into 5 bands with 1px gap
        int gap = Math.max(1, w / 40); // Proportional gap
        int totalGaps = 4 * gap; // 4 gaps between 5 bars
        int barWidth = Math.max(1, (w - totalGaps) / 5);

        for (int band = 0; band < 5; band++) {
            double value = Math.min(1.0, smoothBands[band] + beatBoost);

            // Update peak hold
            if (value >= peaks[band]) {
                peaks[band] = value;
                peakHold[band] = 0;
            } else {
                peakHold[band]++;
                if (peakHold[band] > PEAK_HOLD_FRAMES) {
                    peaks[band] = Math.max(0, peaks[band] - PEAK_DECAY_RATE);
                }
            }

            // Bar position
            int barX = band * (barWidth + gap);

            // Draw bar with VU gradient
            int fillRows = (int) (value * h);
            for (int row = 0; row < fillRows && row < h; row++) {
                // Gradient position: 0 = bottom, 1 = top of bar
                float gradientT = (float) row / h;
                int color;
                if (gradientT < 0.6) {
                    color = BitmapFrameBuffer.lerpColor(COLOR_GREEN, COLOR_YELLOW, gradientT / 0.6f);
                } else {
                    color = BitmapFrameBuffer.lerpColor(COLOR_YELLOW, COLOR_RED, (gradientT - 0.6f) / 0.4f);
                }

                // Map row to screen Y (bottom-up)
                int screenY = h - 1 - row;
                for (int col = barX; col < barX + barWidth && col < w; col++) {
                    buffer.setPixel(col, screenY, color);
                }
            }

            // Draw peak indicator (1px bright line)
            int peakRow = (int) (peaks[band] * (h - 1));
            int peakY = h - 1 - peakRow;
            if (peakY >= 0 && peakY < h && peaks[band] > 0.02) {
                for (int col = barX; col < barX + barWidth && col < w; col++) {
                    buffer.setPixel(col, peakY, COLOR_PEAK);
                }
            }
        }
    }

    @Override
    public void reset() {
        java.util.Arrays.fill(smoothBands, 0);
        java.util.Arrays.fill(peaks, 0);
        java.util.Arrays.fill(peakHold, 0);
    }
}
