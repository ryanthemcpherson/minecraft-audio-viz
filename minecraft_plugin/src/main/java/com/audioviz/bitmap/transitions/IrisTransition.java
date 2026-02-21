package com.audioviz.bitmap.transitions;

import com.audioviz.bitmap.BitmapFrameBuffer;

/**
 * Circular iris transition. A circle expands (or contracts) from the center,
 * revealing the incoming pattern. Classic cinema transition.
 */
public class IrisTransition extends BitmapTransition {

    private final boolean opening; // true = iris open (reveal), false = iris close (hide)
    private final double edgeSoftness;

    public IrisTransition() {
        this(true, 0.06);
    }

    public IrisTransition(boolean opening, double edgeSoftness) {
        super(opening ? "iris_open" : "iris_close", opening ? "Iris Open" : "Iris Close");
        this.opening = opening;
        this.edgeSoftness = Math.max(0.01, edgeSoftness);
    }

    @Override
    public void blend(BitmapFrameBuffer from, BitmapFrameBuffer to,
                      BitmapFrameBuffer output, double progress) {
        double t = ease(progress);
        if (!opening) t = 1.0 - t;

        int w = output.getWidth();
        int h = output.getHeight();
        int[] fromPx = from.getRawPixels();
        int[] toPx = to.getRawPixels();
        int[] outPx = output.getRawPixels();

        double cx = w / 2.0;
        double cy = h / 2.0;
        // Max radius = distance from center to corner
        double maxRadius = Math.sqrt(cx * cx + cy * cy);
        double currentRadius = t * maxRadius * 1.1; // Slight overshoot to ensure full cover

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (idx >= outPx.length) break;

                double dist = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
                double normalizedDist = dist / maxRadius;
                double normalizedRadius = currentRadius / maxRadius;

                // Soft edge
                double localT;
                if (normalizedDist < normalizedRadius - edgeSoftness) {
                    localT = 1.0; // Inside iris = "to"
                } else if (normalizedDist > normalizedRadius) {
                    localT = 0.0; // Outside iris = "from"
                } else {
                    localT = 1.0 - (normalizedDist - (normalizedRadius - edgeSoftness)) / edgeSoftness;
                }

                if (opening) {
                    outPx[idx] = BitmapFrameBuffer.lerpColor(fromPx[idx], toPx[idx], localT);
                } else {
                    outPx[idx] = BitmapFrameBuffer.lerpColor(toPx[idx], fromPx[idx], localT);
                }
            }
        }
    }
}
