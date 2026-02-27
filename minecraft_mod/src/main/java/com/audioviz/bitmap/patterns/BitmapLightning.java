package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Electric arc / lightning bolt effect.
 *
 * <p>On each beat, generates fractal lightning bolts using random midpoint
 * displacement. Bolts have a bright white core with blue glow and fade over time.
 */
public class BitmapLightning extends BitmapPattern {

    private static final int MAX_BOLTS = 4;
    private static final int BG = 0x00000000;
    private static final int CORE_COLOR = BitmapFrameBuffer.rgb(220, 230, 255);
    private static final int GLOW_COLOR = BitmapFrameBuffer.rgb(60, 80, 255);

    private final List<Bolt> bolts = new ArrayList<>();
    private final Random rng = new Random(51);

    public BitmapLightning() {
        super("bmp_lightning", "Bitmap Lightning",
              "Fractal lightning arcs triggered by beats");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        buffer.fill(BG);

        // Ambient purple glow at bottom
        double amplitude = audio.getAmplitude();
        int glowStart = h * 3 / 4;
        int glowRange = h - glowStart;
        if (glowRange < 1) glowRange = 1;
        for (int y = glowStart; y < h; y++) {
            double t = (double) (y - glowStart) / glowRange;
            int glow = BitmapFrameBuffer.lerpColor(BG,
                BitmapFrameBuffer.rgb(15, 5, 30), (float) (t * amplitude * 0.5));
            for (int x = 0; x < w; x++) {
                buffer.setPixel(x, y, glow);
            }
        }

        // Spawn bolt on beat
        if (audio.isBeat() && bolts.size() < MAX_BOLTS) {
            int x1 = w / 4 + rng.nextInt(Math.max(1, w / 2));
            int y1 = 0;
            int x2 = w / 4 + rng.nextInt(Math.max(1, w / 2));
            int y2 = h - 1;
            int[] boltX = generateBolt(x1, y1, x2, y2, w, 5);
            bolts.add(new Bolt(time, boltX, h, audio.getBeatIntensity()));
        }

        // Render bolts
        for (Bolt bolt : bolts) {
            double age = time - bolt.startTime;
            double fadeFactor = Math.max(0, 1.0 - age * 1.5);
            if (fadeFactor <= 0) continue;

            for (int y = 0; y < bolt.height && y < bolt.xPositions.length; y++) {
                int bx = bolt.xPositions[y];

                // Core pixel (bright white)
                double coreBri = fadeFactor * (0.7 + bolt.intensity * 0.3);
                if (bx >= 0 && bx < w) {
                    int existing = buffer.getPixel(bx, y);
                    buffer.setPixel(bx, y, BitmapFrameBuffer.lerpColor(existing, CORE_COLOR, (float) coreBri));
                }

                // Glow (3px wide, dimmer)
                for (int dx = -2; dx <= 2; dx++) {
                    if (dx == 0) continue;
                    int gx = bx + dx;
                    if (gx >= 0 && gx < w) {
                        double glowBri = fadeFactor * 0.4 / (Math.abs(dx));
                        int existing = buffer.getPixel(gx, y);
                        buffer.setPixel(gx, y, BitmapFrameBuffer.lerpColor(existing, GLOW_COLOR, (float) glowBri));
                    }
                }
            }
        }

        // Remove expired bolts
        Iterator<Bolt> it = bolts.iterator();
        while (it.hasNext()) {
            if (time - it.next().startTime > 0.8) it.remove();
        }
    }

    /**
     * Generate a lightning bolt path using recursive midpoint displacement.
     * Returns x-position for each y from top to bottom.
     */
    private int[] generateBolt(int x1, int y1, int x2, int y2, int maxW, int depth) {
        int height = y2 - y1 + 1;
        int[] xPositions = new int[height];

        // Interpolate base line
        for (int y = 0; y < height; y++) {
            double t = (double) y / (height - 1);
            xPositions[y] = (int) (x1 + (x2 - x1) * t);
        }

        // Apply midpoint displacement
        displace(xPositions, 0, height - 1, maxW, depth);
        return xPositions;
    }

    private void displace(int[] xPos, int start, int end, int maxW, int depth) {
        if (depth <= 0 || end - start < 2) return;
        int mid = (start + end) / 2;
        int range = (end - start) / 3;
        xPos[mid] = (xPos[start] + xPos[end]) / 2 + (rng.nextInt(range * 2 + 1) - range);
        xPos[mid] = Math.max(0, Math.min(maxW - 1, xPos[mid]));
        displace(xPos, start, mid, maxW, depth - 1);
        displace(xPos, mid, end, maxW, depth - 1);
    }

    @Override
    public void reset() {
        bolts.clear();
    }

    private static class Bolt {
        final double startTime;
        final int[] xPositions;
        final int height;
        final double intensity;

        Bolt(double startTime, int[] xPositions, int height, double intensity) {
            this.startTime = startTime;
            this.xPositions = xPositions;
            this.height = height;
            this.intensity = intensity;
        }
    }
}
