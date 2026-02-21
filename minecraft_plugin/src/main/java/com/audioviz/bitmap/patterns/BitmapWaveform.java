package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Audio-reactive waveform / oscilloscope display.
 *
 * <p>Renders a glowing waveform line that distorts with audio energy.
 * Multiple frequency bands layer on top of each other as colored waves.
 * Bass creates the dominant wave shape, higher frequencies add fine detail.
 *
 * <p>Beat hits cause the waveform to "explode" outward momentarily.
 */
public class BitmapWaveform extends BitmapPattern {

    private double beatPulse = 0.0;
    private double phase = 0.0;

    // Band colors (bass → high)
    private static final int[] BAND_COLORS = {
        BitmapFrameBuffer.rgb(255, 40, 40),    // Bass - Red
        BitmapFrameBuffer.rgb(255, 160, 0),     // Low-mid - Orange
        BitmapFrameBuffer.rgb(0, 255, 120),     // Mid - Green
        BitmapFrameBuffer.rgb(0, 160, 255),     // High-mid - Blue
        BitmapFrameBuffer.rgb(180, 60, 255)     // High - Purple
    };

    public BitmapWaveform() {
        super("bmp_waveform", "Bitmap Waveform",
              "Audio-reactive oscilloscope with layered frequency waves");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        // Fade previous frame (creates trail/glow effect)
        buffer.applyBrightness(0.3);

        double[] bands = audio.getBands();
        double amplitude = audio.getAmplitude();

        // Beat pulse
        if (audio.isBeat()) {
            beatPulse = 1.0;
        } else {
            beatPulse *= 0.88;
        }

        phase += 0.05 + amplitude * 0.1;

        int centerY = h / 2;

        // Draw each band as a separate wave layer
        for (int band = bands.length - 1; band >= 0; band--) {
            double bandValue = bands[band];
            int color = BAND_COLORS[Math.min(band, BAND_COLORS.length - 1)];

            // Each band has different wave frequency and phase
            double waveFreq = 2.0 + band * 1.5;
            double wavePhase = phase + band * 0.8;
            double waveAmplitude = bandValue * (h * 0.35) + beatPulse * h * 0.1;

            int prevY = -1;
            for (int px = 0; px < w; px++) {
                double nx = (double) px / w;

                // Composite wave shape
                double wave = Math.sin(nx * waveFreq * Math.PI + wavePhase) * waveAmplitude;

                // Add secondary harmonic for richness
                wave += Math.sin(nx * waveFreq * 2 * Math.PI + wavePhase * 1.3)
                        * waveAmplitude * 0.3;

                int py = centerY - (int) wave;
                py = Math.max(0, Math.min(h - 1, py));

                // Draw thick line (2-3px depending on amplitude)
                int thickness = 1 + (int) (bandValue * 2);
                for (int dy = -thickness; dy <= thickness; dy++) {
                    int drawY = py + dy;
                    if (drawY >= 0 && drawY < h) {
                        // Glow: brightness falls off with distance from center line
                        float glow = 1.0f - (float) Math.abs(dy) / (thickness + 1);
                        int glowColor = dimColor(color, glow);
                        buffer.setPixel(px, drawY, glowColor);
                    }
                }

                // Connect to previous column (anti-staircase)
                if (prevY >= 0 && Math.abs(py - prevY) > 1) {
                    int step = py > prevY ? 1 : -1;
                    for (int fy = prevY + step; fy != py; fy += step) {
                        if (fy >= 0 && fy < h) {
                            buffer.setPixel(px, fy, dimColor(color, 0.5f));
                        }
                    }
                }
                prevY = py;
            }
        }

        // Center line (dim reference)
        for (int px = 0; px < w; px++) {
            int existing = buffer.getPixel(px, centerY);
            if (existing == 0 || existing == BitmapFrameBuffer.packARGB(255, 5, 5, 15)) {
                buffer.setPixel(px, centerY, BitmapFrameBuffer.packARGB(255, 30, 30, 50));
            }
        }
    }

    /**
     * Dim a color by a factor (0 = black, 1 = unchanged).
     */
    private static int dimColor(int argb, float factor) {
        int a = (argb >> 24) & 0xFF;
        int r = (int) (((argb >> 16) & 0xFF) * factor);
        int g = (int) (((argb >> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return BitmapFrameBuffer.packARGB(a, r, g, b);
    }

    @Override
    public void reset() {
        beatPulse = 0.0;
        phase = 0.0;
    }
}
