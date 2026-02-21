package com.audioviz.bitmap.transitions;

import com.audioviz.bitmap.BitmapFrameBuffer;

/**
 * Directional wipe transition. A boundary sweeps across the screen,
 * revealing the incoming pattern behind it.
 *
 * <p>Supports four directions and has a soft edge for a polished look.
 */
public class WipeTransition extends BitmapTransition {

    public enum Direction { LEFT, RIGHT, UP, DOWN }

    private final Direction direction;
    private final double edgeSoftness;

    public WipeTransition() {
        this(Direction.LEFT, 0.08);
    }

    public WipeTransition(Direction direction, double edgeSoftness) {
        super("wipe_" + direction.name().toLowerCase(), "Wipe " + direction.name());
        this.direction = direction;
        this.edgeSoftness = Math.max(0.01, edgeSoftness);
    }

    @Override
    public void blend(BitmapFrameBuffer from, BitmapFrameBuffer to,
                      BitmapFrameBuffer output, double progress) {
        double t = ease(progress);
        int w = output.getWidth();
        int h = output.getHeight();
        int[] fromPx = from.getRawPixels();
        int[] toPx = to.getRawPixels();
        int[] outPx = output.getRawPixels();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (idx >= outPx.length) break;

                // Normalized position along wipe axis
                double pos;
                switch (direction) {
                    case LEFT  -> pos = (double) x / w;
                    case RIGHT -> pos = 1.0 - (double) x / w;
                    case DOWN  -> pos = (double) y / h;
                    case UP    -> pos = 1.0 - (double) y / h;
                    default    -> pos = (double) x / w;
                }

                // Soft edge: smooth blend around the wipe boundary
                double boundary = t * (1.0 + edgeSoftness);
                double localT;
                if (pos < boundary - edgeSoftness) {
                    localT = 1.0; // Fully "to"
                } else if (pos > boundary) {
                    localT = 0.0; // Fully "from"
                } else {
                    localT = 1.0 - (pos - (boundary - edgeSoftness)) / edgeSoftness;
                }

                outPx[idx] = BitmapFrameBuffer.lerpColor(fromPx[idx], toPx[idx], localT);
            }
        }
    }
}
