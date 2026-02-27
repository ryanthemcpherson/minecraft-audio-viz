package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.Random;

/**
 * Ink drop / watercolor bloom effect.
 *
 * <p>Beats place color drops that diffuse outward through a 2D grid,
 * creating beautiful watercolor-like blooms. Separate R/G/B channels
 * diffuse independently for color mixing.
 */
public class BitmapInkDrop extends BitmapPattern {

    private double[][] inkR, inkG, inkB;
    private double[][] tmpR, tmpG, tmpB; // Reusable diffusion buffers
    private boolean initialized = false;
    private final Random rng = new Random(17);

    public BitmapInkDrop() {
        super("bmp_inkdrop", "Bitmap Ink Drop",
              "Watercolor bloom effects with color diffusion");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        if (!initialized || inkR == null || inkR.length != w || inkR[0].length != h) {
            inkR = new double[w][h];
            inkG = new double[w][h];
            inkB = new double[w][h];
            tmpR = new double[w][h];
            tmpG = new double[w][h];
            tmpB = new double[w][h];
            initialized = true;
        }

        // Place ink drop on beat
        if (audio.isBeat()) {
            int dropX = 2 + rng.nextInt(Math.max(1, w - 4));
            int dropY = 2 + rng.nextInt(Math.max(1, h - 4));
            int dropRadius = 1 + rng.nextInt(3);

            // Color from dominant band
            double[] bands = audio.getBands();
            int dominant = 0;
            for (int i = 1; i < bands.length; i++) {
                if (bands[i] > bands[dominant]) dominant = i;
            }

            double dropR = 0, dropG = 0, dropB = 0;
            switch (dominant) {
                case 0: dropR = 1.0; dropG = 0.2; dropB = 0.1; break; // Bass: red
                case 1: dropR = 1.0; dropG = 0.6; dropB = 0.1; break; // Low-mid: orange
                case 2: dropR = 0.1; dropG = 1.0; dropB = 0.3; break; // Mid: green
                case 3: dropR = 0.2; dropG = 0.4; dropB = 1.0; break; // High-mid: blue
                case 4: dropR = 0.8; dropG = 0.2; dropB = 1.0; break; // High: purple
            }

            double intensity = 0.7 + audio.getBeatIntensity() * 0.3;
            for (int dy = -dropRadius; dy <= dropRadius; dy++) {
                for (int dx = -dropRadius; dx <= dropRadius; dx++) {
                    int px = dropX + dx;
                    int py = dropY + dy;
                    if (px >= 0 && px < w && py >= 0 && py < h) {
                        double dist = Math.sqrt(dx * dx + dy * dy) / dropRadius;
                        double strength = Math.max(0, 1.0 - dist) * intensity;
                        inkR[px][py] = Math.min(1.0, inkR[px][py] + dropR * strength);
                        inkG[px][py] = Math.min(1.0, inkG[px][py] + dropG * strength);
                        inkB[px][py] = Math.min(1.0, inkB[px][py] + dropB * strength);
                    }
                }
            }
        }

        // Diffusion: each cell spreads to neighbors
        double diffRate = 0.08 + audio.getAmplitude() * 0.04;
        double evapRate = 0.003;

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                double sumR = inkR[x][y], sumG = inkG[x][y], sumB = inkB[x][y];
                int neighbors = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                            sumR += inkR[nx][ny];
                            sumG += inkG[nx][ny];
                            sumB += inkB[nx][ny];
                            neighbors++;
                        }
                    }
                }
                double avgR = sumR / (neighbors + 1);
                double avgG = sumG / (neighbors + 1);
                double avgB = sumB / (neighbors + 1);
                tmpR[x][y] = Math.max(0, inkR[x][y] + (avgR - inkR[x][y]) * diffRate - evapRate);
                tmpG[x][y] = Math.max(0, inkG[x][y] + (avgG - inkG[x][y]) * diffRate - evapRate);
                tmpB[x][y] = Math.max(0, inkB[x][y] + (avgB - inkB[x][y]) * diffRate - evapRate);
            }
        }
        // Swap buffers
        double[][] swp;
        swp = inkR; inkR = tmpR; tmpR = swp;
        swp = inkG; inkG = tmpG; tmpG = swp;
        swp = inkB; inkB = tmpB; tmpB = swp;

        // Render
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = Math.min(255, (int) (inkR[x][y] * 255));
                int g = Math.min(255, (int) (inkG[x][y] * 255));
                int b = Math.min(255, (int) (inkB[x][y] * 255));
                if (r + g + b >= 5) {
                    buffer.setPixel(x, y, BitmapFrameBuffer.rgb(r, g, b));
                }
            }
        }
    }

    @Override
    public void reset() {
        initialized = false;
        inkR = null; inkG = null; inkB = null;
        tmpR = null; tmpG = null; tmpB = null;
    }

    @Override
    public void onResize(int width, int height) {
        initialized = false;
    }
}
