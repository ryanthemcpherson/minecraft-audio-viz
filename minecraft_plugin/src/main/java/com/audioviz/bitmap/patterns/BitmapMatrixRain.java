package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.Random;

/**
 * Matrix digital rain effect — falling green code columns.
 *
 * <p>Each column has an independently moving drop with a bright head and
 * fading trail behind it. Audio drives fall speed and spawn rate.
 */
public class BitmapMatrixRain extends BitmapPattern {

    private static final int HEAD_COLOR = BitmapFrameBuffer.rgb(180, 255, 180);
    private static final int BG_COLOR = 0x00000000;

    private double[] dropY;        // Current y position per column
    private double[] dropSpeed;    // Fall speed per column
    private int[] trailLength;     // Trail length per column
    private int[][] trailBuffer;   // Brightness trail per column per row (0-255)
    private boolean initialized = false;
    private final Random rng = new Random(7);

    public BitmapMatrixRain() {
        super("bmp_matrix", "Bitmap Matrix Rain",
              "Falling digital rain columns with audio-reactive speed");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        if (!initialized || dropY == null || dropY.length != w) {
            initState(w, h);
        }

        double amplitude = audio.getAmplitude();
        double bass = audio.getBass();
        boolean beat = audio.isBeat();

        // Fade existing trails
        buffer.fill(BG_COLOR);

        // Decay trail buffer
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                trailBuffer[x][y] = Math.max(0, trailBuffer[x][y] - 8 - (int) (amplitude * 6));
            }
        }

        // Update drops
        double speedMult = 0.5 + amplitude * 1.5;
        for (int x = 0; x < w; x++) {
            dropY[x] += dropSpeed[x] * speedMult;

            int headY = (int) dropY[x];
            if (headY >= 0 && headY < h) {
                trailBuffer[x][headY] = 255;
            }

            // Respawn when drop exits bottom (or randomly for beat burst)
            if (headY > h + trailLength[x]) {
                respawnDrop(x, h);
            }
        }

        // Beat: spawn extra drops
        if (beat) {
            int spawnCount = 3 + rng.nextInt(Math.max(1, w / 5));
            for (int i = 0; i < spawnCount; i++) {
                int col = rng.nextInt(w);
                respawnDrop(col, h);
                dropSpeed[col] *= 1.5; // Extra fast
            }
        }

        // Render trail buffer to pixels
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int brightness = trailBuffer[x][y];
                if (brightness > 0) {
                    int headY = (int) dropY[x];
                    if (y == headY) {
                        // Head: bright white-green
                        buffer.setPixel(x, y, HEAD_COLOR);
                    } else {
                        // Trail: green with varying brightness + random jitter
                        int g = Math.min(255, brightness + rng.nextInt(30));
                        int r = brightness / 10;
                        int b = brightness / 12;
                        buffer.setPixel(x, y, BitmapFrameBuffer.rgb(r, g, b));
                    }
                }
            }
        }
    }

    private void initState(int w, int h) {
        dropY = new double[w];
        dropSpeed = new double[w];
        trailLength = new int[w];
        trailBuffer = new int[w][h];
        for (int x = 0; x < w; x++) {
            dropY[x] = -rng.nextInt(h * 2); // Stagger start positions
            dropSpeed[x] = 0.3 + rng.nextDouble() * 0.8;
            trailLength[x] = 4 + rng.nextInt(Math.max(1, h / 3));
        }
        initialized = true;
    }

    private void respawnDrop(int col, int h) {
        dropY[col] = -rng.nextInt(Math.max(1, h / 2)) - 1;
        dropSpeed[col] = 0.3 + rng.nextDouble() * 0.8;
        trailLength[col] = 4 + rng.nextInt(Math.max(1, h / 3));
    }

    @Override
    public void reset() {
        initialized = false;
        dropY = null;
    }

    @Override
    public void onResize(int width, int height) {
        initialized = false;
    }
}
