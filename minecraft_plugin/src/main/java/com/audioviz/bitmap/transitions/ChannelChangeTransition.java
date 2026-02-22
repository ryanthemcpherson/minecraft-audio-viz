package com.audioviz.bitmap.transitions;

import com.audioviz.bitmap.BitmapFrameBuffer;

import java.util.Random;

/**
 * TV channel change transition. Mimics the look of switching channels
 * on an old CRT television:
 *
 * <ol>
 *   <li><b>Phase 1 (0–30%)</b>: Old pattern dissolves into increasing static noise</li>
 *   <li><b>Phase 2 (30–60%)</b>: Full static with horizontal scan lines; brief black flash at midpoint</li>
 *   <li><b>Phase 3 (60–100%)</b>: Static fades out, new pattern fades in</li>
 * </ol>
 */
public class ChannelChangeTransition extends BitmapTransition {

    private final Random rng = new Random();

    /** Scan-line darkening: every Nth row is dimmed for CRT effect. */
    private static final int SCAN_LINE_INTERVAL = 4;
    private static final float SCAN_LINE_DIM = 0.35f;

    /** Progress range for the black flash at the midpoint. */
    private static final double FLASH_START = 0.43;
    private static final double FLASH_END = 0.50;

    public ChannelChangeTransition() {
        super("channel_change", "Channel Change");
    }

    @Override
    public void reset() {
        rng.setSeed(System.nanoTime());
    }

    @Override
    protected double ease(double t) {
        // Linear — the phased blending handles all timing
        return t;
    }

    @Override
    public void blend(BitmapFrameBuffer from, BitmapFrameBuffer to,
                      BitmapFrameBuffer output, double progress) {
        int[] fromPx = from.getRawPixels();
        int[] toPx = to.getRawPixels();
        int[] outPx = output.getRawPixels();

        int width = output.getWidth();
        int height = output.getHeight();
        int len = Math.min(outPx.length, Math.min(fromPx.length, toPx.length));

        if (progress < 0.30) {
            // Phase 1: Old pattern → increasing static mix
            double phaseT = progress / 0.30; // 0→1 within this phase
            double staticAmount = phaseT * phaseT; // Accelerating static

            for (int i = 0; i < len; i++) {
                if (rng.nextDouble() < staticAmount) {
                    outPx[i] = staticPixel(i, width, height);
                } else {
                    outPx[i] = fromPx[i];
                }
            }

        } else if (progress < 0.60) {
            // Phase 2: Full static with scan lines + black flash at midpoint
            boolean blackFlash = progress >= FLASH_START && progress < FLASH_END;

            if (blackFlash) {
                // Brief black/near-black frame
                for (int i = 0; i < len; i++) {
                    int row = i / width;
                    // Faint static even during black flash for texture
                    int grey = rng.nextInt(20);
                    int color = BitmapFrameBuffer.packARGB(0xFF, grey, grey, grey);
                    outPx[i] = (row % SCAN_LINE_INTERVAL == 0)
                        ? dimColor(color, 0.2f) : color;
                }
            } else {
                // Full static with scan lines
                for (int i = 0; i < len; i++) {
                    outPx[i] = staticPixel(i, width, height);
                }
            }

        } else {
            // Phase 3: Static fades out → new pattern fades in
            double phaseT = (progress - 0.60) / 0.40; // 0→1 within this phase
            double staticAmount = (1.0 - phaseT) * (1.0 - phaseT); // Decelerating static

            for (int i = 0; i < len; i++) {
                if (rng.nextDouble() < staticAmount) {
                    outPx[i] = staticPixel(i, width, height);
                } else {
                    outPx[i] = toPx[i];
                }
            }
        }
    }

    /**
     * Generate a static/noise pixel with optional scan-line darkening.
     */
    private int staticPixel(int index, int width, int height) {
        int row = index / width;
        // Greyscale noise with slight color tinting for CRT feel
        int base = rng.nextInt(200) + 20;
        int r = Math.min(255, base + rng.nextInt(16) - 8);
        int g = Math.min(255, base + rng.nextInt(16) - 8);
        int b = Math.min(255, base + rng.nextInt(16) - 8);
        int color = BitmapFrameBuffer.packARGB(0xFF, r, g, b);

        // Scan-line effect: dim every Nth row
        if (row % SCAN_LINE_INTERVAL == 0) {
            color = dimColor(color, SCAN_LINE_DIM);
        }

        return color;
    }

    /**
     * Dim a color by a factor (0=black, 1=unchanged).
     */
    private static int dimColor(int argb, float factor) {
        int a = (argb >> 24) & 0xFF;
        int r = (int) (((argb >> 16) & 0xFF) * factor);
        int g = (int) (((argb >> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return BitmapFrameBuffer.packARGB(a, r, g, b);
    }
}
