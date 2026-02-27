package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Radial VU meter / pulsing rings visualization.
 *
 * <p>Concentric rings emanate from the center, each mapped to a frequency band.
 * Ring radius pulses with band energy. Beat hits create expanding shockwave rings.
 * Colors follow the band palette (bass=red core → highs=blue outer).
 *
 * <p>Perfect as a backdrop behind 3D entity patterns in hybrid mode.
 */
public class BitmapVUMeter extends BitmapPattern {

    private double beatPulse = 0.0;
    private double shockwaveRadius = 0.0;
    private boolean shockwaveActive = false;

    // Band colors: warm core to cool edges
    private static final int[] BAND_COLORS = {
        BitmapFrameBuffer.rgb(255, 30, 30),     // Bass - deep red
        BitmapFrameBuffer.rgb(255, 100, 0),     // Low-mid - orange
        BitmapFrameBuffer.rgb(255, 220, 0),     // Mid - gold
        BitmapFrameBuffer.rgb(0, 200, 100),     // High-mid - teal
        BitmapFrameBuffer.rgb(30, 100, 255)     // High - blue
    };

    public BitmapVUMeter() {
        super("bmp_vumeter", "Bitmap VU Meter",
              "Radial pulsing rings with beat shockwaves");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        // Clear to near-black
        buffer.clear();

        double[] bands = audio.getBands();
        double cx = w / 2.0;
        double cy = h / 2.0;
        double maxRadius = Math.min(cx, cy);

        // Beat shockwave
        if (audio.isBeat()) {
            beatPulse = 1.0;
            shockwaveRadius = 0;
            shockwaveActive = true;
        } else {
            beatPulse *= 0.85;
        }

        if (shockwaveActive) {
            shockwaveRadius += maxRadius * 0.08;
            if (shockwaveRadius > maxRadius * 1.5) {
                shockwaveActive = false;
            }
        }

        // Draw rings from outside in (so inner bands overlay outer)
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                double dx = px - cx;
                double dy = py - cy;
                double dist = Math.sqrt(dx * dx + dy * dy);

                // Map distance to band index
                int color = 0;
                for (int band = 4; band >= 0; band--) {
                    double bandRadius = maxRadius * (band + 1) / 5.0;
                    double bandEnergy = bands[band];

                    // Ring expands with energy
                    double ringRadius = bandRadius * (0.4 + bandEnergy * 0.6);
                    double ringThickness = maxRadius / 10.0 + bandEnergy * maxRadius / 8.0;

                    double ringDist = Math.abs(dist - ringRadius);
                    if (ringDist < ringThickness) {
                        float intensity = (float) (1.0 - ringDist / ringThickness);
                        intensity *= (float) (0.4 + bandEnergy * 0.6);
                        color = dimColor(BAND_COLORS[band], intensity);
                    }
                }

                // Shockwave overlay
                if (shockwaveActive) {
                    double swDist = Math.abs(dist - shockwaveRadius);
                    double swThickness = 2.0 + beatPulse * 3.0;
                    if (swDist < swThickness) {
                        float swIntensity = (float) ((1.0 - swDist / swThickness) * beatPulse);
                        int swColor = dimColor(BitmapFrameBuffer.rgb(255, 255, 255), swIntensity);
                        color = addColors(color, swColor);
                    }
                }

                if (color != 0) {
                    buffer.setPixel(px, py, color);
                }
            }
        }
    }

    private static int dimColor(int argb, float factor) {
        factor = Math.max(0, Math.min(1, factor));
        int r = (int) (((argb >> 16) & 0xFF) * factor);
        int g = (int) (((argb >> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return BitmapFrameBuffer.packARGB(255, r, g, b);
    }

    private static int addColors(int c1, int c2) {
        int r = Math.min(255, ((c1 >> 16) & 0xFF) + ((c2 >> 16) & 0xFF));
        int g = Math.min(255, ((c1 >> 8) & 0xFF) + ((c2 >> 8) & 0xFF));
        int b = Math.min(255, (c1 & 0xFF) + (c2 & 0xFF));
        return BitmapFrameBuffer.packARGB(255, r, g, b);
    }

    @Override
    public void reset() {
        beatPulse = 0.0;
        shockwaveRadius = 0.0;
        shockwaveActive = false;
    }
}
