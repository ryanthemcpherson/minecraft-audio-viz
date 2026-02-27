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

        // Render: sum wave contributions at each pixel
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double totalR = 0, totalG = 0, totalB = 0;

                for (RippleWave ripple : ripples) {
                    double age = time - ripple.startTime;
                    double fadeout = Math.max(0, 1.0 - age * 0.4);
                    if (fadeout <= 0) continue;

                    double dist = Math.sqrt((x - ripple.cx) * (x - ripple.cx)
                                          + (y - ripple.cy) * (y - ripple.cy));
                    double waveRadius = age * 20.0; // Expansion speed
                    double waveWidth = 3.0 + age * 0.5;

                    // Sine wave centered on expanding ring
                    double phase = (dist - waveRadius) / waveWidth;
                    double waveVal = Math.exp(-phase * phase) * Math.cos(phase * Math.PI);
                    waveVal *= fadeout * ripple.intensity;

                    if (waveVal > 0) {
                        totalR += ((ripple.color >> 16) & 0xFF) * waveVal / 255.0;
                        totalG += ((ripple.color >> 8) & 0xFF) * waveVal / 255.0;
                        totalB += (ripple.color & 0xFF) * waveVal / 255.0;
                    }
                }

                if (totalR > 0 || totalG > 0 || totalB > 0) {
                    int r = Math.min(255, (int) (totalR * 255));
                    int g = Math.min(255, (int) (totalG * 255));
                    int b = Math.min(255, (int) (totalB * 255));
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
