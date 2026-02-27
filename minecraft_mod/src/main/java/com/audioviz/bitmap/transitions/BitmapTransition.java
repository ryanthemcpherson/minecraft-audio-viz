package com.audioviz.bitmap.transitions;

import com.audioviz.bitmap.BitmapFrameBuffer;

/**
 * Defines a visual transition between two bitmap frames.
 *
 * <p>The transition engine renders both the "from" and "to" patterns into
 * separate buffers, then calls {@link #blend} each tick to produce the
 * output frame. {@code progress} ramps from 0.0 (fully "from") to
 * 1.0 (fully "to") over the transition duration.
 */
public abstract class BitmapTransition {

    private final String id;
    private final String name;

    protected BitmapTransition(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() { return id; }
    public String getName() { return name; }

    /**
     * Blend two frames into the output buffer.
     *
     * @param from     the outgoing pattern's frame
     * @param to       the incoming pattern's frame
     * @param output   the buffer to write the blended result into
     * @param progress 0.0 = fully "from", 1.0 = fully "to"
     */
    public abstract void blend(BitmapFrameBuffer from, BitmapFrameBuffer to,
                               BitmapFrameBuffer output, double progress);

    /**
     * Reset any internal state (called before a new transition starts).
     */
    public void reset() {
        // Override if needed
    }

    /**
     * Apply an easing curve to progress. Subclasses can override for
     * custom easing; default is smooth ease-in-out.
     */
    protected double ease(double t) {
        // Smooth step: 3t² - 2t³
        return t * t * (3.0 - 2.0 * t);
    }
}
