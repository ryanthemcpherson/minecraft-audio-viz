package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Moiré interference pattern from overlapping concentric circles.
 *
 * <p>Two sets of concentric circles at different centers create classic
 * optical interference patterns. Centers orbit each other; audio shifts
 * spacing and position.
 */
public class BitmapMoire extends BitmapPattern {

    public BitmapMoire() {
        super("bmp_moire", "Bitmap Moiré",
              "Overlapping circle patterns creating interference illusions");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        double cx = w / 2.0;
        double cy = h / 2.0;

        double bass = audio.getBass();
        double mid = audio.getMid();
        double amplitude = audio.getAmplitude();

        // Two centers orbiting each other
        double orbitRadius = 0.15 + mid * 0.1;
        double orbitSpeed = time * (0.3 + bass * 0.5);

        double c1x = cx + Math.cos(orbitSpeed) * cx * orbitRadius;
        double c1y = cy + Math.sin(orbitSpeed) * cy * orbitRadius;
        double c2x = cx + Math.cos(orbitSpeed + Math.PI) * cx * orbitRadius;
        double c2y = cy + Math.sin(orbitSpeed + Math.PI) * cy * orbitRadius;

        // Third center (slower orbit)
        double c3x = cx + Math.cos(orbitSpeed * 0.7 + 2) * cx * orbitRadius * 0.7;
        double c3y = cy + Math.sin(orbitSpeed * 0.7 + 2) * cy * orbitRadius * 0.7;

        // Ring spacing varies with bass
        double spacing = 2.5 + bass * 1.5;

        float hueShift = (float) (time * 10);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double d1 = Math.sqrt((x - c1x) * (x - c1x) + (y - c1y) * (y - c1y));
                double d2 = Math.sqrt((x - c2x) * (x - c2x) + (y - c2y) * (y - c2y));
                double d3 = Math.sqrt((x - c3x) * (x - c3x) + (y - c3y) * (y - c3y));

                // Concentric ring patterns (0 or 1 alternating)
                double v1 = Math.sin(d1 / spacing * Math.PI);
                double v2 = Math.sin(d2 / spacing * Math.PI);
                double v3 = Math.sin(d3 / spacing * Math.PI * 0.8);

                // Interference: multiply patterns
                double moireVal = (v1 * v2 + v1 * v3 + v2 * v3) / 3.0;
                moireVal = (moireVal + 1.0) / 2.0; // Normalize to 0-1

                // Subtle hue tinting from audio
                float hue = (float) ((moireVal * 60 + hueShift) % 360);
                float sat = 0.2f + (float) (amplitude * 0.4);
                float bri = (float) Math.min(1.0, moireVal * (0.5 + amplitude * 0.5));

                int color = BitmapFrameBuffer.fromHSB(hue, sat, bri);
                buffer.setPixel(x, y, color);
            }
        }
    }

    @Override
    public void reset() {
        // Stateless
    }
}
