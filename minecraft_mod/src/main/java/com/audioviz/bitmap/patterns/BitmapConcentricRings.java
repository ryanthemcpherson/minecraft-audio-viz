package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Expanding concentric rings spawned on beats.
 *
 * <p>Each beat spawns a new ring at the center, colored by the dominant
 * frequency band. Rings expand outward and fade with distance.
 */
public class BitmapConcentricRings extends BitmapPattern {

    private static final int MAX_RINGS = 15;

    private final List<Ring> rings = new ArrayList<>();

    public BitmapConcentricRings() {
        super("bmp_rings", "Bitmap Concentric Rings",
              "Expanding beat-spawned rings from center");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        double cx = w / 2.0;
        double cy = h / 2.0;
        double maxRadius = Math.sqrt(cx * cx + cy * cy);

        buffer.clear();

        // Spawn ring on beat
        if (audio.isBeat() && rings.size() < MAX_RINGS) {
            double[] bands = audio.getBands();
            int dominantBand = 0;
            for (int i = 1; i < bands.length; i++) {
                if (bands[i] > bands[dominantBand]) dominantBand = i;
            }
            // Hue mapped from band: bass=0°(red), mid=120°(green), high=240°(blue)
            float hue = dominantBand * 72.0f; // 0, 72, 144, 216, 288
            int color = BitmapFrameBuffer.fromHSB(hue, 0.9f, 1.0f);
            rings.add(new Ring(time, color, 1 + (int) (audio.getBeatIntensity() * 2)));
        }

        // Render all active rings
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dist = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));

                int r = 0, g = 0, b = 0;
                for (Ring ring : rings) {
                    double age = time - ring.startTime;
                    double radius = age * 15.0; // Expansion speed
                    double thickness = ring.thickness + age * 0.5;
                    double fadeFactor = Math.max(0, 1.0 - age * 0.4); // Fade over time

                    double ringDist = Math.abs(dist - radius);
                    if (ringDist < thickness) {
                        double edgeFade = 1.0 - ringDist / thickness;
                        double intensity = edgeFade * fadeFactor;
                        int rc = (int) (((ring.color >> 16) & 0xFF) * intensity);
                        int gc = (int) (((ring.color >> 8) & 0xFF) * intensity);
                        int bc = (int) ((ring.color & 0xFF) * intensity);
                        r = Math.min(255, r + rc);
                        g = Math.min(255, g + gc);
                        b = Math.min(255, b + bc);
                    }
                }

                if (r > 0 || g > 0 || b > 0) {
                    buffer.setPixel(x, y, BitmapFrameBuffer.rgb(r, g, b));
                }
            }
        }

        // Remove expired rings
        Iterator<Ring> it = rings.iterator();
        while (it.hasNext()) {
            Ring ring = it.next();
            double age = time - ring.startTime;
            if (age > 3.0 || age * 15.0 > maxRadius * 1.5) {
                it.remove();
            }
        }
    }

    @Override
    public void reset() {
        rings.clear();
    }

    private static class Ring {
        final double startTime;
        final int color;
        final int thickness;

        Ring(double startTime, int color, int thickness) {
            this.startTime = startTime;
            this.color = color;
            this.thickness = thickness;
        }
    }
}
