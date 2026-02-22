package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Radial burst — rays exploding outward from center on beat.
 *
 * <p>Each beat spawns a set of 8-16 rays that extend from center, fading
 * with distance and age. Multiple bursts can overlap additively.
 */
public class BitmapRadialBurst extends BitmapPattern {

    private static final int MAX_BURSTS = 5;
    private final List<Burst> bursts = new ArrayList<>();
    private final Random rng = new Random(13);

    public BitmapRadialBurst() {
        super("bmp_burst", "Bitmap Radial Burst",
              "Exploding ray bursts from center on beats");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        double cx = w / 2.0;
        double cy = h / 2.0;

        buffer.clear();

        // Spawn burst on beat
        if (audio.isBeat() && bursts.size() < MAX_BURSTS) {
            int numRays = 8 + rng.nextInt(9); // 8-16 rays
            double angleOffset = rng.nextDouble() * Math.PI * 2;
            float hue = (float) (audio.getBass() * 60 + audio.getMid() * 180) % 360;
            int color = BitmapFrameBuffer.fromHSB(hue, 0.85f, 1.0f);
            bursts.add(new Burst(time, numRays, angleOffset, color));
        }

        // Render bursts
        for (Burst burst : bursts) {
            double age = time - burst.startTime;
            double rayLength = age * 25.0; // Pixels per second
            double fadeFactor = Math.max(0, 1.0 - age * 0.5);

            for (int r = 0; r < burst.numRays; r++) {
                double angle = burst.angleOffset + (r * Math.PI * 2.0 / burst.numRays);
                double dx = Math.cos(angle);
                double dy = Math.sin(angle);

                // Walk along ray
                int maxLen = (int) Math.min(rayLength, Math.max(w, h));
                for (int step = 2; step < maxLen; step++) {
                    int px = (int) (cx + dx * step);
                    int py = (int) (cy + dy * step);
                    if (px < 0 || px >= w || py < 0 || py >= h) break;

                    // Intensity fades with distance and age
                    double distFade = 1.0 - (double) step / maxLen;
                    double intensity = distFade * fadeFactor;
                    if (intensity < 0.02) continue;

                    int existing = buffer.getPixel(px, py);
                    int blended = BitmapFrameBuffer.lerpColor(existing, burst.color, (float) intensity);
                    buffer.setPixel(px, py, blended);
                }
            }
        }

        // Remove expired bursts
        Iterator<Burst> it = bursts.iterator();
        while (it.hasNext()) {
            if (time - it.next().startTime > 2.5) it.remove();
        }
    }

    @Override
    public void reset() {
        bursts.clear();
    }

    private static class Burst {
        final double startTime;
        final int numRays;
        final double angleOffset;
        final int color;

        Burst(double startTime, int numRays, double angleOffset, int color) {
            this.startTime = startTime;
            this.numRays = numRays;
            this.angleOffset = angleOffset;
            this.color = color;
        }
    }
}
