package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Horizontal scanning sweep with CRT/retro aesthetic.
 *
 * <p>A bright horizontal band sweeps up/down the screen, revealing
 * audio-reactive spectrum data. CRT scan line overlay on every other row.
 */
public class BitmapScanLines extends BitmapPattern {

    private double scanY = 0;
    private double scanDirection = 1;
    private double beatPulse = 0;

    private static final int PHOSPHOR_GREEN = BitmapFrameBuffer.rgb(30, 255, 100);
    private static final int BG = 0x00000000;

    public BitmapScanLines() {
        super("bmp_scanlines", "Bitmap Scan Lines",
              "CRT-style horizontal sweep with retro phosphor glow");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        double amplitude = audio.getAmplitude();
        double bass = audio.getBass();
        double mid = audio.getMid();

        if (audio.isBeat()) {
            beatPulse = 1.0;
            // Occasionally reverse direction
            if (audio.getBeatIntensity() > 0.7) {
                scanDirection = -scanDirection;
            }
        }
        beatPulse *= 0.88;

        // Move scan line
        double speed = 0.5 + mid * 1.5 + beatPulse * 2.0;
        scanY += scanDirection * speed;
        if (scanY > h) { scanY = h; scanDirection = -1; }
        if (scanY < 0) { scanY = 0; scanDirection = 1; }

        double scanWidth = 3 + bass * 4; // Width of bright scan band

        // Render: spectrum data as horizontal brightness
        double[] bands = audio.getBands();
        for (int y = 0; y < h; y++) {
            // CRT scan line effect: dim every other row
            boolean scanLineRow = y % 2 == 0;
            double scanLineDim = scanLineRow ? 1.0 : 0.5;

            // Map row to frequency band
            double freqNorm = 1.0 - (double) y / (h - 1);
            double bandFloat = freqNorm * (bands.length - 1);
            int bandLo = Math.min((int) bandFloat, bands.length - 2);
            double frac = bandFloat - bandLo;
            double bandValue = bands[bandLo] * (1 - frac) + bands[bandLo + 1] * frac;

            // Scan bar proximity brightness
            double scanDist = Math.abs(y - scanY);
            double scanBrightness = 0;
            if (scanDist < scanWidth) {
                scanBrightness = 1.0 - scanDist / scanWidth;
                scanBrightness *= scanBrightness; // Sharper falloff
            }

            for (int x = 0; x < w; x++) {
                // Base content: horizontal bars per band value
                double nx = (double) x / w;
                double contentBri = bandValue * (0.3 + amplitude * 0.4);

                // Add subtle noise for CRT texture
                contentBri += Math.sin(x * 0.5 + y * 0.3 + time * 10) * 0.03;

                // Combine with scan bar
                double totalBri = contentBri * 0.4 + scanBrightness * 0.8;
                totalBri *= scanLineDim;
                totalBri = Math.min(1.0, totalBri);

                if (totalBri < 0.01) {
                    buffer.setPixel(x, y, BG);
                } else {
                    int color = BitmapFrameBuffer.lerpColor(BG, PHOSPHOR_GREEN, (float) totalBri);
                    buffer.setPixel(x, y, color);
                }
            }
        }
    }

    @Override
    public void reset() {
        scanY = 0;
        scanDirection = 1;
        beatPulse = 0;
    }
}
