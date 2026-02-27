package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Circular spectrum analyzer — EQ bars arranged in a ring.
 *
 * <p>5 frequency bands are distributed around a central ring, with bars
 * extending outward. Iconic festival LED wall effect.
 */
public class BitmapCircularSpectrum extends BitmapPattern {

    private final double[] smoothBands = new double[5];
    private double beatPulse = 0;

    // Band colors: bass(red) → high(blue)
    private static final int[] BAND_COLORS = {
        BitmapFrameBuffer.rgb(255, 40, 40),
        BitmapFrameBuffer.rgb(255, 180, 30),
        BitmapFrameBuffer.rgb(50, 255, 80),
        BitmapFrameBuffer.rgb(40, 140, 255),
        BitmapFrameBuffer.rgb(180, 60, 255),
    };

    public BitmapCircularSpectrum() {
        super("bmp_circular_spectrum", "Bitmap Circular Spectrum",
              "Frequency bars arranged in a ring with radial extension");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        double cx = w / 2.0;
        double cy = h / 2.0;
        double maxR = Math.min(cx, cy) * 0.9;
        double innerR = maxR * 0.3;

        buffer.clear();

        // Smooth bands
        double[] bands = audio.getBands();
        for (int i = 0; i < 5; i++) {
            smoothBands[i] = smoothBands[i] * 0.4 + bands[i] * 0.6;
        }

        if (audio.isBeat()) beatPulse = 1.0;
        beatPulse *= 0.85;

        // Interpolate 5 bands to 20 thin bars around the ring
        int numBars = 20;
        double[] barValues = new double[numBars];
        for (int i = 0; i < numBars; i++) {
            double bandFloat = (double) i / numBars * (smoothBands.length - 1);
            int lo = (int) bandFloat;
            int hi = Math.min(lo + 1, smoothBands.length - 1);
            double frac = bandFloat - lo;
            barValues[i] = smoothBands[lo] * (1 - frac) + smoothBands[hi] * frac;
        }

        // Draw bars as angular wedges
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = x - cx;
                double dy = y - cy;
                double dist = Math.sqrt(dx * dx + dy * dy);

                if (dist < innerR - 1 || dist > maxR) continue;

                // Inner ring glow
                if (dist < innerR + 1) {
                    double pulse = 0.3 + beatPulse * 0.5 + audio.getAmplitude() * 0.2;
                    float hue = (float) ((time * 30) % 360);
                    int ringColor = BitmapFrameBuffer.fromHSB(hue, 0.6f, (float) Math.min(1, pulse));
                    buffer.setPixel(x, y, ringColor);
                    continue;
                }

                double angle = Math.atan2(dy, dx);
                if (angle < 0) angle += Math.PI * 2;

                // Which bar does this angle fall in?
                int barIdx = (int) (angle / (Math.PI * 2) * numBars);
                barIdx = Math.min(barIdx, numBars - 1);

                // Angular position within the bar (for gap between bars)
                double barAngle = angle - barIdx * (Math.PI * 2 / numBars);
                double barWidth = Math.PI * 2 / numBars;
                double normalizedInBar = barAngle / barWidth;
                if (normalizedInBar < 0.1 || normalizedInBar > 0.9) continue; // Gap

                // Bar extends from innerR to innerR + barValue * (maxR - innerR)
                double barLength = innerR + barValues[barIdx] * (maxR - innerR);
                if (dist > barLength) continue;

                // Color: interpolate band colors
                double bandFloat = (double) barIdx / numBars * (BAND_COLORS.length - 1);
                int lo = (int) bandFloat;
                int hi = Math.min(lo + 1, BAND_COLORS.length - 1);
                float frac = (float) (bandFloat - lo);
                int color = BitmapFrameBuffer.lerpColor(BAND_COLORS[lo], BAND_COLORS[hi], frac);

                // Brightness varies with distance (brighter at tip)
                double distNorm = (dist - innerR) / (barLength - innerR);
                float bri = (float) (0.5 + distNorm * 0.5);
                color = BitmapFrameBuffer.lerpColor(BitmapFrameBuffer.packARGB(255, 3, 3, 10), color, bri);

                buffer.setPixel(x, y, color);
            }
        }
    }

    @Override
    public void reset() {
        java.util.Arrays.fill(smoothBands, 0);
        beatPulse = 0;
    }
}
