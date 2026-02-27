package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.Random;

/**
 * Downward-falling colored particles with fading trails.
 *
 * <p>Particles are band-colored (bass=red, mid=green, high=blue) and
 * fall at varying speeds. Audio drives spawn rate and brightness.
 */
public class BitmapParticleRain extends BitmapPattern {

    private static final int MAX_PARTICLES = 200;

    private double[] px, py, speed;
    private int[] color;
    private int[] trailLen;
    private int activeCount = 0;
    private boolean initialized = false;
    private final Random rng = new Random(37);

    // Band colors
    private static final int[] BAND_COLORS = {
        BitmapFrameBuffer.rgb(255, 40, 40),   // Bass: red
        BitmapFrameBuffer.rgb(255, 160, 30),   // Low-mid: orange
        BitmapFrameBuffer.rgb(30, 255, 80),    // Mid: green
        BitmapFrameBuffer.rgb(50, 120, 255),   // High-mid: blue
        BitmapFrameBuffer.rgb(180, 80, 255),   // High: purple
    };

    public BitmapParticleRain() {
        super("bmp_rain", "Bitmap Particle Rain",
              "Falling band-colored particles with trails");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        if (!initialized) {
            px = new double[MAX_PARTICLES];
            py = new double[MAX_PARTICLES];
            speed = new double[MAX_PARTICLES];
            color = new int[MAX_PARTICLES];
            trailLen = new int[MAX_PARTICLES];
            activeCount = 0;
            initialized = true;
        }

        buffer.clear();

        double amplitude = audio.getAmplitude();
        double bass = audio.getBass();
        double[] bands = audio.getBands();

        // Spawn particles based on audio energy
        int spawnCount = (int) (amplitude * 4) + 1;
        if (audio.isBeat()) spawnCount += 5 + (int) (audio.getBeatIntensity() * 8);

        for (int s = 0; s < spawnCount && activeCount < MAX_PARTICLES; s++) {
            int i = activeCount++;
            px[i] = rng.nextInt(w);
            py[i] = -rng.nextInt(3);
            speed[i] = 0.5 + rng.nextDouble() * 2.0;
            trailLen[i] = 3 + rng.nextInt(5);

            // Color from random band, weighted by energy
            int band = weightedBandPick(bands);
            color[i] = BAND_COLORS[band];
        }

        // Update and render particles
        int alive = 0;
        for (int i = 0; i < activeCount; i++) {
            py[i] += speed[i] * (0.5 + amplitude);

            int headY = (int) py[i];
            int headX = (int) px[i];

            // Draw trail
            for (int t = 0; t < trailLen[i]; t++) {
                int ty = headY - t;
                if (ty < 0 || ty >= h || headX < 0 || headX >= w) continue;

                double fade = 1.0 - (double) t / trailLen[i];
                fade *= fade; // Exponential falloff
                float brightness = (float) (fade * (0.5 + bass * 0.5));

                int c = color[i];
                int r = (int) (((c >> 16) & 0xFF) * brightness);
                int g = (int) (((c >> 8) & 0xFF) * brightness);
                int b = (int) ((c & 0xFF) * brightness);
                int dimmed = BitmapFrameBuffer.rgb(r, g, b);

                // Additive blend
                int existing = buffer.getPixel(headX, ty);
                int er = Math.min(255, ((existing >> 16) & 0xFF) + r);
                int eg = Math.min(255, ((existing >> 8) & 0xFF) + g);
                int eb = Math.min(255, (existing & 0xFF) + b);
                buffer.setPixel(headX, ty, BitmapFrameBuffer.rgb(er, eg, eb));
            }

            // Keep alive if on screen
            if (headY < h + trailLen[i] + 2) {
                if (alive != i) {
                    px[alive] = px[i]; py[alive] = py[i];
                    speed[alive] = speed[i]; color[alive] = color[i];
                    trailLen[alive] = trailLen[i];
                }
                alive++;
            }
        }
        activeCount = alive;
    }

    private int weightedBandPick(double[] bands) {
        double total = 0;
        for (double b : bands) total += b + 0.1;
        double pick = rng.nextDouble() * total;
        double sum = 0;
        for (int i = 0; i < bands.length; i++) {
            sum += bands[i] + 0.1;
            if (pick <= sum) return i;
        }
        return bands.length - 1;
    }

    @Override
    public void reset() {
        initialized = false;
        activeCount = 0;
    }

    @Override
    public void onResize(int width, int height) {
        initialized = false;
    }
}
