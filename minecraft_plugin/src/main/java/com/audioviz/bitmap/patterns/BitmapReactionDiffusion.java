package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.Random;

/**
 * Gray-Scott reaction-diffusion cellular automata.
 *
 * <p>Two chemicals react and diffuse across a grid, creating organic
 * coral/brain/maze textures. Audio modulates feed and kill rates to
 * morph between pattern types. Beats inject chemical at random points.
 *
 * <p>Runs at half internal resolution and upscales for performance.
 */
public class BitmapReactionDiffusion extends BitmapPattern {

    private double[] gridA, gridB;
    private double[] nextA, nextB;
    private int simW, simH;
    private int cachedW = -1, cachedH = -1;
    private final Random rng = new Random(42);
    private double beatPulse = 0;

    // Gray-Scott defaults (coral pattern)
    private static final double DA = 1.0;   // Diffusion rate A
    private static final double DB = 0.5;   // Diffusion rate B
    private static final double BASE_FEED = 0.055;
    private static final double BASE_KILL = 0.062;

    public BitmapReactionDiffusion() {
        super("bmp_reaction_diffusion", "Bitmap Reaction Diffusion",
              "Organic coral/maze textures from Gray-Scott automata");
    }

    private void ensureGrids(int outW, int outH) {
        if (outW == cachedW && outH == cachedH) return;
        cachedW = outW;
        cachedH = outH;
        // Half resolution for simulation
        simW = Math.max(4, outW / 2);
        simH = Math.max(4, outH / 2);
        int size = simW * simH;
        gridA = new double[size];
        gridB = new double[size];
        nextA = new double[size];
        nextB = new double[size];
        // Initialize: A=1 everywhere, B=0 except some seed spots
        java.util.Arrays.fill(gridA, 1.0);
        java.util.Arrays.fill(gridB, 0.0);
        seedInitialPatterns();
    }

    private void seedInitialPatterns() {
        // Seed several small squares of chemical B
        for (int s = 0; s < 5; s++) {
            int cx = simW / 4 + rng.nextInt(simW / 2);
            int cy = simH / 4 + rng.nextInt(simH / 2);
            int r = 2 + rng.nextInt(2);
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    int x = (cx + dx + simW) % simW;
                    int y = (cy + dy + simH) % simH;
                    gridB[y * simW + x] = 1.0;
                }
            }
        }
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int outW = buffer.getWidth();
        int outH = buffer.getHeight();
        ensureGrids(outW, outH);

        double bass = audio.getBass();
        double mid = audio.getMid();
        double amplitude = audio.getAmplitude();

        if (audio.isBeat()) {
            beatPulse = 1.0;
            // Inject chemical B at a random spot
            int cx = rng.nextInt(simW);
            int cy = rng.nextInt(simH);
            int r = 2 + rng.nextInt(3);
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    int x = (cx + dx + simW) % simW;
                    int y = (cy + dy + simH) % simH;
                    gridB[y * simW + x] = Math.min(1.0, gridB[y * simW + x] + 0.8);
                }
            }
        }
        beatPulse *= 0.9;

        // Audio-modulated parameters
        double feed = BASE_FEED + bass * 0.015 - mid * 0.005;
        double kill = BASE_KILL + mid * 0.008 - bass * 0.003;
        feed = Math.max(0.01, Math.min(0.1, feed));
        kill = Math.max(0.04, Math.min(0.08, kill));

        // Run 2-4 simulation steps per frame for responsiveness
        int steps = 2 + (int) (amplitude * 2);
        for (int step = 0; step < steps; step++) {
            simulationStep(feed, kill);
        }

        // Render to output buffer (nearest-neighbor upscale from sim grid)
        int[] pixels = buffer.getRawPixels();
        double hueShift = time * 10;

        for (int py = 0; py < outH; py++) {
            int sy = py * simH / outH;
            for (int px = 0; px < outW; px++) {
                int sx = px * simW / outW;
                int si = sy * simW + sx;

                double a = gridA[si];
                double b = gridB[si];

                // Color from chemical concentrations
                double intensity = b * 2.0;
                intensity = Math.max(0, Math.min(1, intensity));

                float hue = (float) ((intensity * 120 + hueShift + a * 60) % 360);
                float sat = (float) (0.6 + intensity * 0.4);
                float bri = (float) (intensity * 0.8 + amplitude * 0.15 + beatPulse * 0.1);
                bri = Math.min(1.0f, bri);

                if (bri < 0.02) {
                    pixels[py * outW + px] = 0xFF000000;
                } else {
                    pixels[py * outW + px] = BitmapFrameBuffer.fromHSB(hue, sat, bri);
                }
            }
        }
    }

    private void simulationStep(double feed, double kill) {
        for (int y = 0; y < simH; y++) {
            for (int x = 0; x < simW; x++) {
                int i = y * simW + x;
                double a = gridA[i];
                double b = gridB[i];

                // 5-point Laplacian with wrapping
                double lapA = laplacian(gridA, x, y);
                double lapB = laplacian(gridB, x, y);

                double abb = a * b * b;
                nextA[i] = a + (DA * lapA - abb + feed * (1.0 - a));
                nextB[i] = b + (DB * lapB + abb - (kill + feed) * b);

                nextA[i] = Math.max(0, Math.min(1, nextA[i]));
                nextB[i] = Math.max(0, Math.min(1, nextB[i]));
            }
        }
        // Swap buffers
        double[] tmpA = gridA; gridA = nextA; nextA = tmpA;
        double[] tmpB = gridB; gridB = nextB; nextB = tmpB;
    }

    private double laplacian(double[] grid, int x, int y) {
        int xm = (x - 1 + simW) % simW;
        int xp = (x + 1) % simW;
        int ym = (y - 1 + simH) % simH;
        int yp = (y + 1) % simH;
        int i = y * simW + x;
        return grid[ym * simW + x] + grid[yp * simW + x]
             + grid[y * simW + xm] + grid[y * simW + xp]
             - 4.0 * grid[i];
    }

    @Override
    public void reset() {
        beatPulse = 0;
        cachedW = -1;
        cachedH = -1;
    }
}
