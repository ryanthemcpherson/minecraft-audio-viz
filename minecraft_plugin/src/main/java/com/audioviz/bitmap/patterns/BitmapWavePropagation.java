package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Multiple layered sine waves traveling across the screen.
 *
 * <p>One wave per frequency band, each at different frequency and amplitude.
 * Waves are additive where they overlap. Bass = large slow waves,
 * highs = small fast waves. Very organic, flowing feel.
 */
public class BitmapWavePropagation extends BitmapPattern {

    // Wave properties per band: [frequency multiplier, speed, vertical center offset]
    private static final double[][] WAVE_PROPS = {
        {1.0, 0.3, 0.0},    // Bass: slow, large
        {1.8, 0.5, -0.1},   // Low-mid
        {3.0, 0.8, 0.05},   // Mid
        {5.0, 1.2, -0.05},  // High-mid: fast, small
        {8.0, 1.8, 0.1},    // High: fastest, smallest
    };

    private static final int[] WAVE_COLORS = {
        BitmapFrameBuffer.rgb(255, 50, 50),   // Bass: red
        BitmapFrameBuffer.rgb(255, 180, 40),  // Low-mid: orange
        BitmapFrameBuffer.rgb(50, 255, 100),  // Mid: green
        BitmapFrameBuffer.rgb(60, 140, 255),  // High-mid: blue
        BitmapFrameBuffer.rgb(200, 80, 255),  // High: purple
    };

    public BitmapWavePropagation() {
        super("bmp_waves", "Bitmap Wave Propagation",
              "Layered traveling waves per frequency band");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        buffer.clear();

        double[] bands = audio.getBands();
        double amplitude = audio.getAmplitude();

        for (int x = 0; x < w; x++) {
            double nx = (double) x / w;

            // Accumulate wave contributions per pixel column
            double[] columnR = new double[h];
            double[] columnG = new double[h];
            double[] columnB = new double[h];

            for (int b = 0; b < Math.min(5, bands.length); b++) {
                double freq = WAVE_PROPS[b][0];
                double speed = WAVE_PROPS[b][1];
                double centerOffset = WAVE_PROPS[b][2];

                double bandEnergy = bands[b];
                double waveAmplitude = bandEnergy * h * 0.3; // Wave height in pixels
                double phase = nx * freq * Math.PI * 2 - time * speed * Math.PI * 2;
                double waveY = (0.5 + centerOffset) * h + Math.sin(phase) * waveAmplitude;

                // Draw wave as gaussian blob around waveY
                double thickness = 1.5 + bandEnergy * 2.0;
                int cr = (WAVE_COLORS[b] >> 16) & 0xFF;
                int cg = (WAVE_COLORS[b] >> 8) & 0xFF;
                int cb = WAVE_COLORS[b] & 0xFF;

                for (int y = 0; y < h; y++) {
                    double dist = Math.abs(y - waveY);
                    if (dist > thickness * 4) continue;
                    double intensity = Math.exp(-dist * dist / (2 * thickness * thickness));
                    intensity *= bandEnergy * (0.5 + amplitude * 0.5);
                    columnR[y] += cr * intensity / 255.0;
                    columnG[y] += cg * intensity / 255.0;
                    columnB[y] += cb * intensity / 255.0;
                }
            }

            // Write accumulated column
            for (int y = 0; y < h; y++) {
                if (columnR[y] > 0.01 || columnG[y] > 0.01 || columnB[y] > 0.01) {
                    int r = Math.min(255, (int) (columnR[y] * 255));
                    int g = Math.min(255, (int) (columnG[y] * 255));
                    int bv = Math.min(255, (int) (columnB[y] * 255));
                    buffer.setPixel(x, y, BitmapFrameBuffer.rgb(r, g, bv));
                }
            }
        }
    }

    @Override
    public void reset() {
        // Stateless
    }
}
