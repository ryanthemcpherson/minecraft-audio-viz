package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.Arrays;

/**
 * Glitch art pixel sorting effect.
 *
 * <p>Renders a plasma base layer, then sorts pixel rows by brightness
 * above an audio-driven threshold. Creates the classic data-bending
 * aesthetic popular in VJ culture.
 */
public class BitmapPixelSort extends BitmapPattern {

    private double beatPulse = 0;
    private boolean sortDirection = false; // false = left-to-right, true = right-to-left

    // Pre-allocated scratch buffers for sortRun() — sized to buffer width
    private int[] sortScratchPixels = new int[0];
    private long[] sortScratchKeyed = new long[0];

    public BitmapPixelSort() {
        super("bmp_pixelsort", "Bitmap Pixel Sort",
              "Glitch art pixel sorting with audio-reactive threshold");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        double bass = audio.getBass();
        double mid = audio.getMid();
        double amplitude = audio.getAmplitude();

        if (audio.isBeat()) {
            beatPulse = 1.0;
            sortDirection = !sortDirection;
        }
        beatPulse *= 0.85;

        // Step 1: Render plasma base pattern
        double speed = time * (0.6 + bass);
        for (int y = 0; y < h; y++) {
            double ny = (double) y / h;
            for (int x = 0; x < w; x++) {
                double nx = (double) x / w;
                double v1 = Math.sin(nx * 4 + speed);
                double v2 = Math.sin(ny * 3 + speed * 0.8);
                double v3 = Math.sin((nx + ny) * 2.5 + speed * 1.3);
                double plasma = (v1 + v2 + v3 + 3) / 6.0;

                float hue = (float) ((plasma * 200 + time * 25 + mid * 80) % 360);
                float sat = 0.85f;
                float bri = (float) (0.3 + plasma * 0.5 + amplitude * 0.2);
                buffer.setPixel(x, y, BitmapFrameBuffer.fromHSB(hue, sat, Math.min(1, bri)));
            }
        }

        // Step 2: Sort pixel rows by brightness above threshold
        double threshold = 0.3 + (1.0 - amplitude) * 0.4; // More sorting at high amplitude
        threshold -= beatPulse * 0.3; // Beat drops threshold (more sorting)
        threshold = Math.max(0.05, Math.min(0.95, threshold));

        int thresholdInt = (int) (threshold * 255);

        for (int y = 0; y < h; y++) {
            // Find runs of pixels above brightness threshold
            int runStart = -1;
            for (int x = 0; x <= w; x++) {
                int brightness = 0;
                if (x < w) {
                    int pixel = buffer.getPixel(x, y);
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int b = pixel & 0xFF;
                    brightness = (r + g + b) / 3;
                }

                if (brightness > thresholdInt && x < w) {
                    if (runStart < 0) runStart = x;
                } else {
                    if (runStart >= 0) {
                        // Sort this run
                        sortRun(buffer, y, runStart, x - 1, sortDirection);
                        runStart = -1;
                    }
                }
            }
        }
    }

    private void sortRun(BitmapFrameBuffer buffer, int y, int start, int end, boolean reverse) {
        int len = end - start + 1;
        if (len < 2) return;

        // Reuse scratch buffers, resizing only if needed
        if (sortScratchPixels.length < len) {
            sortScratchPixels = new int[len];
            sortScratchKeyed = new long[len];
        }
        int[] run = sortScratchPixels;
        for (int i = 0; i < len; i++) {
            run[i] = buffer.getPixel(start + i, y);
        }

        // Sort by brightness using a keyed sort (pack brightness + index)
        long[] keyed = sortScratchKeyed;
        for (int i = 0; i < len; i++) {
            int bri = brightness(run[i]);
            keyed[i] = ((long) bri << 32) | i;
        }
        Arrays.sort(keyed);

        if (reverse) {
            for (int i = 0; i < len; i++) {
                buffer.setPixel(start + i, y, run[(int) (keyed[len - 1 - i] & 0xFFFFFFFFL)]);
            }
        } else {
            for (int i = 0; i < len; i++) {
                buffer.setPixel(start + i, y, run[(int) (keyed[i] & 0xFFFFFFFFL)]);
            }
        }
    }

    private static int brightness(int argb) {
        return ((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF);
    }

    @Override
    public void reset() {
        beatPulse = 0;
        sortDirection = false;
    }
}
