package com.audioviz.bitmap.transitions;

import com.audioviz.bitmap.BitmapFrameBuffer;

import java.util.Random;

/**
 * TV channel change transition — dissolves into static noise, then resolves to the new pattern.
 */
public class ChannelChangeTransition extends BitmapTransition {

    private final Random rng = new Random();

    private static final int SCAN_LINE_INTERVAL = 4;
    private static final float SCAN_LINE_DIM = 0.35f;
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
        return t; // Linear — phased blending handles timing
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
            double phaseT = progress / 0.30;
            double staticAmount = phaseT * phaseT;

            for (int i = 0; i < len; i++) {
                if (rng.nextDouble() < staticAmount) {
                    outPx[i] = staticPixel(i, width, height);
                } else {
                    outPx[i] = fromPx[i];
                }
            }

        } else if (progress < 0.60) {
            boolean blackFlash = progress >= FLASH_START && progress < FLASH_END;

            if (blackFlash) {
                for (int i = 0; i < len; i++) {
                    int row = i / width;
                    int grey = rng.nextInt(20);
                    int color = BitmapFrameBuffer.packARGB(0xFF, grey, grey, grey);
                    outPx[i] = (row % SCAN_LINE_INTERVAL == 0)
                        ? dimColor(color, 0.2f) : color;
                }
            } else {
                for (int i = 0; i < len; i++) {
                    outPx[i] = staticPixel(i, width, height);
                }
            }

        } else {
            double phaseT = (progress - 0.60) / 0.40;
            double staticAmount = (1.0 - phaseT) * (1.0 - phaseT);

            for (int i = 0; i < len; i++) {
                if (rng.nextDouble() < staticAmount) {
                    outPx[i] = staticPixel(i, width, height);
                } else {
                    outPx[i] = toPx[i];
                }
            }
        }
    }

    private int staticPixel(int index, int width, int height) {
        int row = index / width;
        int base = rng.nextInt(200) + 20;
        int r = Math.min(255, base + rng.nextInt(16) - 8);
        int g = Math.min(255, base + rng.nextInt(16) - 8);
        int b = Math.min(255, base + rng.nextInt(16) - 8);
        int color = BitmapFrameBuffer.packARGB(0xFF, r, g, b);

        if (row % SCAN_LINE_INTERVAL == 0) {
            color = dimColor(color, SCAN_LINE_DIM);
        }

        return color;
    }

    private static int dimColor(int argb, float factor) {
        int a = (argb >> 24) & 0xFF;
        int r = (int) (((argb >> 16) & 0xFF) * factor);
        int g = (int) (((argb >> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return BitmapFrameBuffer.packARGB(a, r, g, b);
    }
}
