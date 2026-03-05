package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.Random;

/**
 * Voronoi diagram rendered as stained glass that breathes with audio.
 *
 * <p>Seeds drift slowly, cells pulse with their mapped frequency band,
 * and beats cause cells to "shatter" — seeds temporarily split and
 * recombine. Dark borders glow with amplitude.
 */
public class BitmapVoronoiShatter extends BitmapPattern {

    private static final int BASE_SEEDS = 25;
    private static final int MAX_SHATTER_SEEDS = 20;

    // Seed state: [x, y, vx, vy, bandIndex, hueOffset, life]
    // life: -1 = permanent, >0 = temporary (shatter fragment)
    private double[][] seeds;
    private int totalSlots;
    private double beatPulse = 0;
    private double shatterIntensity = 0;
    private final Random rng = new Random(123);

    public BitmapVoronoiShatter() {
        super("bmp_voronoi", "Bitmap Voronoi Shatter",
              "Stained glass cells that breathe and shatter on beats");
        initSeeds();
    }

    private void initSeeds() {
        totalSlots = BASE_SEEDS + MAX_SHATTER_SEEDS;
        seeds = new double[totalSlots][7];
        for (int i = 0; i < BASE_SEEDS; i++) {
            seeds[i][0] = Math.random();       // x
            seeds[i][1] = Math.random();       // y
            seeds[i][2] = (Math.random() - 0.5) * 0.2; // vx
            seeds[i][3] = (Math.random() - 0.5) * 0.2; // vy
            seeds[i][4] = i % 5;               // band index
            seeds[i][5] = i * (360.0 / BASE_SEEDS); // hue offset
            seeds[i][6] = -1;                  // permanent
        }
        for (int i = BASE_SEEDS; i < totalSlots; i++) {
            seeds[i][6] = 0; // inactive
        }
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        int[] pixels = buffer.getRawPixels();

        double bass = audio.getBass();
        double mid = audio.getMid();
        double amplitude = audio.getAmplitude();
        double[] bands = audio.getBands();

        if (audio.isBeat()) {
            beatPulse = 1.0;
            shatterIntensity = Math.min(1.0, shatterIntensity + 0.4);

            // Spawn shatter fragments near random existing seeds
            int fragmentsToSpawn = 2 + (int) (audio.getBeatIntensity() * 3);
            for (int f = 0; f < fragmentsToSpawn; f++) {
                int parentIdx = rng.nextInt(BASE_SEEDS);
                for (int i = BASE_SEEDS; i < totalSlots; i++) {
                    if (seeds[i][6] <= 0) {
                        seeds[i][0] = seeds[parentIdx][0] + (rng.nextDouble() - 0.5) * 0.08;
                        seeds[i][1] = seeds[parentIdx][1] + (rng.nextDouble() - 0.5) * 0.08;
                        seeds[i][2] = (rng.nextDouble() - 0.5) * 0.4;
                        seeds[i][3] = (rng.nextDouble() - 0.5) * 0.4;
                        seeds[i][4] = seeds[parentIdx][4];
                        seeds[i][5] = seeds[parentIdx][5] + rng.nextDouble() * 30;
                        seeds[i][6] = 1.0; // life
                        break;
                    }
                }
            }
        }
        beatPulse *= 0.88;
        shatterIntensity *= 0.97;

        // Update seed positions
        double speed = 0.01 + bass * 0.02;
        for (int i = 0; i < totalSlots; i++) {
            if (i >= BASE_SEEDS && seeds[i][6] <= 0) continue;

            seeds[i][0] += seeds[i][2] * speed;
            seeds[i][1] += seeds[i][3] * speed;

            // Wrap around
            seeds[i][0] = ((seeds[i][0] % 1.0) + 1.0) % 1.0;
            seeds[i][1] = ((seeds[i][1] % 1.0) + 1.0) % 1.0;

            // Decay shatter fragments
            if (seeds[i][6] > 0) seeds[i][6] -= 0.008;
        }

        // Render Voronoi
        double hueBase = time * 8;

        for (int py = 0; py < h; py++) {
            double ny = (double) py / h;
            for (int px = 0; px < w; px++) {
                double nx = (double) px / w;

                // Find closest and second-closest seed
                double minDist = Double.MAX_VALUE;
                double secondDist = Double.MAX_VALUE;
                int closestIdx = 0;

                for (int i = 0; i < totalSlots; i++) {
                    if (i >= BASE_SEEDS && seeds[i][6] <= 0) continue;

                    double dx = nx - seeds[i][0];
                    double dy = ny - seeds[i][1];
                    // Handle wrapping for toroidal distance
                    if (dx > 0.5) dx -= 1.0; else if (dx < -0.5) dx += 1.0;
                    if (dy > 0.5) dy -= 1.0; else if (dy < -0.5) dy += 1.0;
                    double dist = dx * dx + dy * dy;

                    if (dist < minDist) {
                        secondDist = minDist;
                        minDist = dist;
                        closestIdx = i;
                    } else if (dist < secondDist) {
                        secondDist = dist;
                    }
                }

                double edgeDist = Math.sqrt(secondDist) - Math.sqrt(minDist);

                int bandIdx = (int) seeds[closestIdx][4];
                double bandVal = bands[Math.min(bandIdx, 4)];
                double seedHue = seeds[closestIdx][5];

                // Life multiplier for shatter fragments
                double lifeMul = seeds[closestIdx][6] > 0 ? seeds[closestIdx][6] : 1.0;

                if (edgeDist < 0.015 + amplitude * 0.01) {
                    // Border: dark with glow
                    float bri = (float) (amplitude * 0.5 + beatPulse * 0.4);
                    float hue = (float) ((hueBase + seedHue) % 360);
                    pixels[py * w + px] = BitmapFrameBuffer.fromHSB(hue, 0.3f, bri * (float) lifeMul);
                } else {
                    // Cell interior
                    float hue = (float) ((seedHue + hueBase + bandVal * 30) % 360);
                    float sat = (float) (0.6 + bandVal * 0.3);
                    float bri = (float) (0.3 + bandVal * 0.5 + beatPulse * 0.15);
                    bri = Math.min(1.0f, bri) * (float) lifeMul;
                    pixels[py * w + px] = BitmapFrameBuffer.fromHSB(hue, sat, bri);
                }
            }
        }
    }

    @Override
    public void reset() {
        beatPulse = 0;
        shatterIntensity = 0;
        initSeeds();
    }
}
