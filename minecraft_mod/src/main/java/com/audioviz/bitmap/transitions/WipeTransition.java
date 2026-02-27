package com.audioviz.bitmap.transitions;

import com.audioviz.bitmap.BitmapFrameBuffer;

/**
 * Directional wipe transition with soft edge.
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

                double pos = switch (direction) {
                    case LEFT  -> (double) x / w;
                    case RIGHT -> 1.0 - (double) x / w;
                    case DOWN  -> (double) y / h;
                    case UP    -> 1.0 - (double) y / h;
                };

                double boundary = t * (1.0 + edgeSoftness);
                double localT;
                if (pos < boundary - edgeSoftness) {
                    localT = 1.0;
                } else if (pos > boundary) {
                    localT = 0.0;
                } else {
                    localT = 1.0 - (pos - (boundary - edgeSoftness)) / edgeSoftness;
                }

                outPx[idx] = BitmapFrameBuffer.lerpColor(fromPx[idx], toPx[idx], localT);
            }
        }
    }
}
