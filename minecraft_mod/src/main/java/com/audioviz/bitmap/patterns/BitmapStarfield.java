package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.Random;

/**
 * Hyperspace starfield rushing from center toward the viewer.
 *
 * <p>3D star positions are projected to 2D with perspective. Stars move
 * along the Z axis toward the camera, creating streak lines as they pass.
 * Bass drives warp speed, beats trigger a speed burst.
 */
public class BitmapStarfield extends BitmapPattern {

    private static final int NUM_STARS = 120;
    private static final double MAX_Z = 10.0;
    private static final double FOCAL = 30.0;

    private double[] starX, starY, starZ;
    private double[] prevScreenX, prevScreenY;
    private boolean initialized = false;
    private final Random rng = new Random(99);
    private double beatBoost = 0;

    public BitmapStarfield() {
        super("bmp_starfield", "Bitmap Starfield",
              "Hyperspace warp effect with audio-reactive speed");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        double cx = w / 2.0;
        double cy = h / 2.0;

        if (!initialized) {
            initStars();
        }

        buffer.clear();

        double bass = audio.getBass();
        double amplitude = audio.getAmplitude();

        if (audio.isBeat()) {
            beatBoost = 1.0;
        } else {
            beatBoost *= 0.85;
        }

        double speed = 0.05 + bass * 0.15 + beatBoost * 0.2 + amplitude * 0.05;

        for (int i = 0; i < NUM_STARS; i++) {
            // Project current position
            double sx = cx + (starX[i] / starZ[i]) * FOCAL;
            double sy = cy + (starY[i] / starZ[i]) * FOCAL;

            // Move star toward camera
            starZ[i] -= speed;

            // Draw streak from previous to current position
            if (prevScreenX[i] >= 0 && starZ[i] > 0.05) {
                double brightness = Math.min(1.0, (1.0 - starZ[i] / MAX_Z) + amplitude * 0.3);
                int grey = (int) (brightness * 255);
                int color = BitmapFrameBuffer.rgb(
                    Math.min(255, grey + (int) (beatBoost * 60)),
                    Math.min(255, grey + (int) (beatBoost * 30)),
                    Math.min(255, grey)
                );

                drawLine(buffer, (int) prevScreenX[i], (int) prevScreenY[i],
                         (int) sx, (int) sy, color, w, h);
            }

            prevScreenX[i] = sx;
            prevScreenY[i] = sy;

            // Respawn if off-screen or past camera
            if (starZ[i] <= 0.05 || sx < -10 || sx > w + 10 || sy < -10 || sy > h + 10) {
                respawnStar(i);
            }
        }
    }

    private void drawLine(BitmapFrameBuffer buffer, int x0, int y0, int x1, int y1,
                          int color, int w, int h) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        int steps = Math.max(dx, dy);
        if (steps > 50) steps = 50; // Cap line length

        for (int s = 0; s <= steps; s++) {
            if (x0 >= 0 && x0 < w && y0 >= 0 && y0 < h) {
                buffer.setPixel(x0, y0, color);
            }
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x0 += sx; }
            if (e2 < dx) { err += dx; y0 += sy; }
        }
    }

    private void initStars() {
        starX = new double[NUM_STARS];
        starY = new double[NUM_STARS];
        starZ = new double[NUM_STARS];
        prevScreenX = new double[NUM_STARS];
        prevScreenY = new double[NUM_STARS];
        for (int i = 0; i < NUM_STARS; i++) {
            respawnStar(i);
            starZ[i] = rng.nextDouble() * MAX_Z; // Spread initial z
            prevScreenX[i] = -1;
        }
        initialized = true;
    }

    private void respawnStar(int i) {
        starX[i] = (rng.nextDouble() - 0.5) * 6.0;
        starY[i] = (rng.nextDouble() - 0.5) * 6.0;
        starZ[i] = MAX_Z * (0.5 + rng.nextDouble() * 0.5);
        prevScreenX[i] = -1;
        prevScreenY[i] = -1;
    }

    @Override
    public void reset() {
        initialized = false;
        beatBoost = 0;
    }
}
