package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.Random;

/**
 * Lava lamp / mercury metaball effect.
 *
 * <p>Multiple blobs drift and merge organically using distance-field
 * iso-surface thresholding. Audio drives blob size, drift speed, and
 * color cycling. Beats spawn temporary extra blobs.
 */
public class BitmapMetaballs extends BitmapPattern {

    private static final int BASE_BLOB_COUNT = 7;
    private static final int MAX_EXTRA_BLOBS = 4;
    private static final double THRESHOLD = 1.0;

    // Blob state: [x, y, vx, vy, radius, bandIndex, life]
    // life: > 0 for temporary blobs (counts down), -1 for permanent
    private double[][] blobs;
    private int totalSlots;
    private double beatPulse = 0;
    private final Random rng = new Random(7);

    public BitmapMetaballs() {
        super("bmp_metaballs", "Bitmap Metaballs",
              "Lava lamp blobs that merge and split with audio");
        initBlobs();
    }

    private void initBlobs() {
        totalSlots = BASE_BLOB_COUNT + MAX_EXTRA_BLOBS;
        blobs = new double[totalSlots][7];
        for (int i = 0; i < BASE_BLOB_COUNT; i++) {
            blobs[i][0] = 0.2 + rng.nextDouble() * 0.6; // x
            blobs[i][1] = 0.2 + rng.nextDouble() * 0.6; // y
            blobs[i][2] = (rng.nextDouble() - 0.5) * 0.3; // vx
            blobs[i][3] = (rng.nextDouble() - 0.5) * 0.3; // vy
            blobs[i][4] = 0.08 + rng.nextDouble() * 0.06; // radius
            blobs[i][5] = i % 5; // band index
            blobs[i][6] = -1; // permanent
        }
        for (int i = BASE_BLOB_COUNT; i < totalSlots; i++) {
            blobs[i][6] = 0; // inactive
        }
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        int[] pixels = buffer.getRawPixels();

        double bass = audio.getBass();
        double mid = audio.getMid();
        double high = audio.getHigh();
        double amplitude = audio.getAmplitude();
        double[] bands = audio.getBands();

        if (audio.isBeat()) {
            beatPulse = 1.0;
            // Spawn a temporary blob
            for (int i = BASE_BLOB_COUNT; i < totalSlots; i++) {
                if (blobs[i][6] <= 0) {
                    blobs[i][0] = 0.3 + rng.nextDouble() * 0.4;
                    blobs[i][1] = 0.3 + rng.nextDouble() * 0.4;
                    blobs[i][2] = (rng.nextDouble() - 0.5) * 0.5;
                    blobs[i][3] = (rng.nextDouble() - 0.5) * 0.5;
                    blobs[i][4] = 0.06 + audio.getBeatIntensity() * 0.04;
                    blobs[i][5] = rng.nextInt(5);
                    blobs[i][6] = 1.0; // life
                    break;
                }
            }
        }
        beatPulse *= 0.9;

        // Update blob positions
        double speedMul = 0.02 + bass * 0.04;
        for (int i = 0; i < totalSlots; i++) {
            if (i >= BASE_BLOB_COUNT && blobs[i][6] <= 0) continue;

            blobs[i][0] += blobs[i][2] * speedMul;
            blobs[i][1] += blobs[i][3] * speedMul;

            // Bounce off edges
            if (blobs[i][0] < 0.05 || blobs[i][0] > 0.95) blobs[i][2] = -blobs[i][2];
            if (blobs[i][1] < 0.05 || blobs[i][1] > 0.95) blobs[i][3] = -blobs[i][3];
            blobs[i][0] = Math.max(0.02, Math.min(0.98, blobs[i][0]));
            blobs[i][1] = Math.max(0.02, Math.min(0.98, blobs[i][1]));

            // Decay temporary blobs
            if (blobs[i][6] > 0) {
                blobs[i][6] -= 0.015;
            }
        }

        // Render distance field
        double hueBase = time * 15;
        for (int py = 0; py < h; py++) {
            double ny = (double) py / h;
            for (int px = 0; px < w; px++) {
                double nx = (double) px / w;

                double field = 0;
                double weightedBand = 0;
                double totalWeight = 0;

                for (int i = 0; i < totalSlots; i++) {
                    if (i >= BASE_BLOB_COUNT && blobs[i][6] <= 0) continue;

                    double dx = nx - blobs[i][0];
                    double dy = ny - blobs[i][1];
                    double dist2 = dx * dx + dy * dy;

                    int bandIdx = (int) blobs[i][5];
                    double bandVal = bands[Math.min(bandIdx, 4)];
                    double r = blobs[i][4] * (1.0 + bandVal * 0.5 + beatPulse * 0.3);

                    // Life fading for temporary blobs
                    if (blobs[i][6] > 0) r *= blobs[i][6];

                    double contribution = (r * r) / (dist2 + 0.001);
                    field += contribution;
                    weightedBand += bandIdx * contribution;
                    totalWeight += contribution;
                }

                if (field > THRESHOLD) {
                    // Inside the blob surface
                    double excess = Math.min(3.0, (field - THRESHOLD) / THRESHOLD);
                    double dominantBand = totalWeight > 0 ? weightedBand / totalWeight : 0;

                    // Iridescent color: hue from band + position + time
                    float hue = (float) ((dominantBand * 60 + hueBase + excess * 30) % 360);
                    float sat = (float) (0.7 + high * 0.3);
                    float bri = (float) Math.min(1.0, 0.5 + excess * 0.3 + amplitude * 0.2);

                    pixels[py * w + px] = BitmapFrameBuffer.fromHSB(hue, sat, bri);
                } else if (field > THRESHOLD * 0.7) {
                    // Edge glow
                    double edge = (field - THRESHOLD * 0.7) / (THRESHOLD * 0.3);
                    float hue = (float) ((hueBase + 180) % 360);
                    float bri = (float) (edge * 0.4);
                    pixels[py * w + px] = BitmapFrameBuffer.fromHSB(hue, 0.5f, bri);
                } else {
                    pixels[py * w + px] = 0xFF000000; // Black background
                }
            }
        }
    }

    @Override
    public void reset() {
        beatPulse = 0;
        initBlobs();
    }
}
