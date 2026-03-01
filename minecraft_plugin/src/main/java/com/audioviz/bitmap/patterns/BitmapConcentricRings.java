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

        // Render all active rings (loop-inverted: iterate per ring, not per pixel)
        int[] rBuf = new int[w * h];
        int[] gBuf = new int[w * h];
        int[] bBuf = new int[w * h];

        for (Ring ring : rings) {
            double age = time - ring.startTime;
            double radius = age * 15.0;
            double thickness = ring.thickness + age * 0.5;
            double fadeFactor = Math.max(0, 1.0 - age * 0.4);
            if (fadeFactor <= 0) continue;

            int ringR = (ring.color >> 16) & 0xFF;
            int ringG = (ring.color >> 8) & 0xFF;
            int ringB = ring.color & 0xFF;

            double rInner = radius - thickness;
            double rOuter = radius + thickness;
            if (rOuter < 0) continue;
            double rInnerSq = rInner > 0 ? rInner * rInner : 0;
            double rOuterSq = rOuter * rOuter;

            int yMin = Math.max(0, (int) Math.floor(cy - rOuter));
            int yMax = Math.min(h - 1, (int) Math.ceil(cy + rOuter));

            for (int y = yMin; y <= yMax; y++) {
                double dy = y - cy;
                double dySq = dy * dy;
                if (dySq > rOuterSq) continue;

                double xSpanOuter = Math.sqrt(rOuterSq - dySq);
                int xMinOuter = Math.max(0, (int) Math.floor(cx - xSpanOuter));
                int xMaxOuter = Math.min(w - 1, (int) Math.ceil(cx + xSpanOuter));

                if (rInner > 0 && dySq < rInnerSq) {
                    double xSpanInner = Math.sqrt(rInnerSq - dySq);
                    int xMinInner = (int) Math.ceil(cx - xSpanInner);
                    int xMaxInner = (int) Math.floor(cx + xSpanInner);

                    for (int x = xMinOuter; x < xMinInner && x <= xMaxOuter; x++) {
                        double dist = Math.sqrt((x - cx) * (x - cx) + dySq);
                        double ringDist = Math.abs(dist - radius);
                        if (ringDist < thickness) {
                            double intensity = (1.0 - ringDist / thickness) * fadeFactor;
                            int idx = y * w + x;
                            rBuf[idx] = Math.min(255, rBuf[idx] + (int) (ringR * intensity));
                            gBuf[idx] = Math.min(255, gBuf[idx] + (int) (ringG * intensity));
                            bBuf[idx] = Math.min(255, bBuf[idx] + (int) (ringB * intensity));
                        }
                    }
                    for (int x = Math.max(xMaxInner + 1, xMinOuter); x <= xMaxOuter; x++) {
                        double dist = Math.sqrt((x - cx) * (x - cx) + dySq);
                        double ringDist = Math.abs(dist - radius);
                        if (ringDist < thickness) {
                            double intensity = (1.0 - ringDist / thickness) * fadeFactor;
                            int idx = y * w + x;
                            rBuf[idx] = Math.min(255, rBuf[idx] + (int) (ringR * intensity));
                            gBuf[idx] = Math.min(255, gBuf[idx] + (int) (ringG * intensity));
                            bBuf[idx] = Math.min(255, bBuf[idx] + (int) (ringB * intensity));
                        }
                    }
                } else {
                    for (int x = xMinOuter; x <= xMaxOuter; x++) {
                        double dist = Math.sqrt((x - cx) * (x - cx) + dySq);
                        double ringDist = Math.abs(dist - radius);
                        if (ringDist < thickness) {
                            double intensity = (1.0 - ringDist / thickness) * fadeFactor;
                            int idx = y * w + x;
                            rBuf[idx] = Math.min(255, rBuf[idx] + (int) (ringR * intensity));
                            gBuf[idx] = Math.min(255, gBuf[idx] + (int) (ringG * intensity));
                            bBuf[idx] = Math.min(255, bBuf[idx] + (int) (ringB * intensity));
                        }
                    }
                }
            }
        }

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (rBuf[idx] > 0 || gBuf[idx] > 0 || bBuf[idx] > 0) {
                    buffer.setPixel(x, y, BitmapFrameBuffer.rgb(rBuf[idx], gBuf[idx], bBuf[idx]));
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
