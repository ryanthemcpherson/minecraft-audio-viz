package com.audioviz.bitmap.transitions;

import com.audioviz.bitmap.BitmapFrameBuffer;

/**
 * Random dissolve transition. Pixels switch from "from" to "to" in a
 * pseudo-random order, creating a TV-static-like dissolve effect.
 *
 * <p>Uses a deterministic hash (not Random) so the dissolve pattern is
 * consistent across frames and doesn't flicker.
 */
public class DissolveTransition extends BitmapTransition {

    /** Seed for the spatial hash — changes per transition instance. */
    private int seed;

    public DissolveTransition() {
        super("dissolve", "Dissolve");
        this.seed = 42;
    }

    @Override
    public void reset() {
        // New seed each transition for visual variety
        seed = (int) (System.nanoTime() & 0x7FFFFFFF);
    }

    @Override
    public void blend(BitmapFrameBuffer from, BitmapFrameBuffer to,
                      BitmapFrameBuffer output, double progress) {
        double t = ease(progress);
        int[] fromPx = from.getRawPixels();
        int[] toPx = to.getRawPixels();
        int[] outPx = output.getRawPixels();

        int len = Math.min(outPx.length, Math.min(fromPx.length, toPx.length));

        for (int i = 0; i < len; i++) {
            // Deterministic per-pixel threshold using spatial hash
            double threshold = spatialHash(i, seed);
            outPx[i] = (threshold < t) ? toPx[i] : fromPx[i];
        }
    }

    /**
     * Spatial hash that maps pixel index to a stable 0-1 value.
     * Produces even distribution so the dissolve doesn't clump.
     */
    private static double spatialHash(int index, int seed) {
        int n = index * 374761393 + seed;
        n = (n ^ (n >> 13)) * 1274126177;
        n = n ^ (n >> 16);
        return (n & 0x7FFFFFFF) / (double) 0x7FFFFFFF;
    }
}
