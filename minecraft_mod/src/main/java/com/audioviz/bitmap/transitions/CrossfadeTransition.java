package com.audioviz.bitmap.transitions;

import com.audioviz.bitmap.BitmapFrameBuffer;

/**
 * Smooth crossfade between two frames via per-pixel color interpolation.
 * The most natural-looking transition — both patterns remain fully visible
 * throughout, blending smoothly.
 */
public class CrossfadeTransition extends BitmapTransition {

    public CrossfadeTransition() {
        super("crossfade", "Crossfade");
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
            outPx[i] = BitmapFrameBuffer.lerpColor(fromPx[i], toPx[i], t);
        }
    }
}
