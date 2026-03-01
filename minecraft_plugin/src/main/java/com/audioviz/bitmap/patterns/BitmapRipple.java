package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Concentric ripple/shockwaves from beat impact points.
 *
 * <p>Beats spawn expanding ring waves at random positions.
 * Each pixel sums sine-wave contributions from all active ripples.
 */
public class BitmapRipple extends BitmapPattern {

    private static final int MAX_RIPPLES = 8;
    private final List<RippleWave> ripples = new ArrayList<>();
    private final Random rng = new Random(23);

    public BitmapRipple() {
        super("bmp_ripple", "Bitmap Ripple",
              "Expanding shockwaves from beat impact points");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        buffer.clear();

        // Spawn ripple on beat
        if (audio.isBeat() && ripples.size() < MAX_RIPPLES) {
            double rx = rng.nextDouble() * w;
            double ry = rng.nextDouble() * h;
            double[] bands = audio.getBands();
            int dominant = 0;
            for (int i = 1; i < bands.length; i++) {
                if (bands[i] > bands[dominant]) dominant = i;
            }
            float hue = dominant * 72.0f;
            int color = BitmapFrameBuffer.fromHSB(hue, 0.8f, 1.0f);
            ripples.add(new RippleWave(time, rx, ry, color, audio.getBeatIntensity()));
        }

        // Render: loop-inverted (iterate per ripple, not per pixel)
        double[] rBuf = new double[w * h];
        double[] gBuf = new double[w * h];
        double[] bBuf = new double[w * h];

        for (RippleWave ripple : ripples) {
            double age = time - ripple.startTime;
            double fadeout = Math.max(0, 1.0 - age * 0.4);
            if (fadeout <= 0) continue;

            double waveRadius = age * 20.0;
            double waveWidth = 3.0 + age * 0.5;

            double rInner = waveRadius - waveWidth * 3.0;
            double rOuter = waveRadius + waveWidth * 3.0;
            if (rOuter < 0) continue;
            double rInnerSq = rInner > 0 ? rInner * rInner : 0;
            double rOuterSq = rOuter * rOuter;

            double rippleR = ((ripple.color >> 16) & 0xFF) / 255.0 * fadeout * ripple.intensity;
            double rippleG = ((ripple.color >> 8) & 0xFF) / 255.0 * fadeout * ripple.intensity;
            double rippleB = (ripple.color & 0xFF) / 255.0 * fadeout * ripple.intensity;

            int yMin = Math.max(0, (int) Math.floor(ripple.cy - rOuter));
            int yMax = Math.min(h - 1, (int) Math.ceil(ripple.cy + rOuter));

            for (int y = yMin; y <= yMax; y++) {
                double dy = y - ripple.cy;
                double dySq = dy * dy;
                if (dySq > rOuterSq) continue;

                double xSpanOuter = Math.sqrt(rOuterSq - dySq);
                int xMinOuter = Math.max(0, (int) Math.floor(ripple.cx - xSpanOuter));
                int xMaxOuter = Math.min(w - 1, (int) Math.ceil(ripple.cx + xSpanOuter));

                if (rInner > 0 && dySq < rInnerSq) {
                    double xSpanInner = Math.sqrt(rInnerSq - dySq);
                    int xMinInner = (int) Math.ceil(ripple.cx - xSpanInner);
                    int xMaxInner = (int) Math.floor(ripple.cx + xSpanInner);

                    for (int x = xMinOuter; x < xMinInner && x <= xMaxOuter; x++) {
                        double dist = Math.sqrt((x - ripple.cx) * (x - ripple.cx) + dySq);
                        double phase = (dist - waveRadius) / waveWidth;
                        double waveVal = Math.exp(-phase * phase) * Math.cos(phase * Math.PI);
                        if (waveVal > 0) {
                            int idx = y * w + x;
                            rBuf[idx] += rippleR * waveVal;
                            gBuf[idx] += rippleG * waveVal;
                            bBuf[idx] += rippleB * waveVal;
                        }
                    }
                    for (int x = Math.max(xMaxInner + 1, xMinOuter); x <= xMaxOuter; x++) {
                        double dist = Math.sqrt((x - ripple.cx) * (x - ripple.cx) + dySq);
                        double phase = (dist - waveRadius) / waveWidth;
                        double waveVal = Math.exp(-phase * phase) * Math.cos(phase * Math.PI);
                        if (waveVal > 0) {
                            int idx = y * w + x;
                            rBuf[idx] += rippleR * waveVal;
                            gBuf[idx] += rippleG * waveVal;
                            bBuf[idx] += rippleB * waveVal;
                        }
                    }
                } else {
                    for (int x = xMinOuter; x <= xMaxOuter; x++) {
                        double dist = Math.sqrt((x - ripple.cx) * (x - ripple.cx) + dySq);
                        double phase = (dist - waveRadius) / waveWidth;
                        double waveVal = Math.exp(-phase * phase) * Math.cos(phase * Math.PI);
                        if (waveVal > 0) {
                            int idx = y * w + x;
                            rBuf[idx] += rippleR * waveVal;
                            gBuf[idx] += rippleG * waveVal;
                            bBuf[idx] += rippleB * waveVal;
                        }
                    }
                }
            }
        }

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (rBuf[idx] > 0 || gBuf[idx] > 0 || bBuf[idx] > 0) {
                    int r = Math.min(255, (int) (rBuf[idx] * 255));
                    int g = Math.min(255, (int) (gBuf[idx] * 255));
                    int b = Math.min(255, (int) (bBuf[idx] * 255));
                    buffer.setPixel(x, y, BitmapFrameBuffer.rgb(r, g, b));
                }
            }
        }

        // Remove expired ripples
        Iterator<RippleWave> it = ripples.iterator();
        while (it.hasNext()) {
            if (time - it.next().startTime > 3.0) it.remove();
        }
    }

    @Override
    public void reset() {
        ripples.clear();
    }

    private static class RippleWave {
        final double startTime, cx, cy, intensity;
        final int color;

        RippleWave(double startTime, double cx, double cy, int color, double intensity) {
            this.startTime = startTime;
            this.cx = cx;
            this.cy = cy;
            this.color = color;
            this.intensity = 0.5 + intensity * 0.5;
        }
    }
}
