package com.audioviz.bitmap.transitions;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.*;

/**
 * Manages pattern transitions with double-buffering.
 *
 * <p>When a transition is triggered, both the outgoing and incoming patterns
 * render into separate buffers each tick. The active {@link BitmapTransition}
 * blends them into the output buffer based on elapsed progress.
 *
 * <p>After the transition completes, the outgoing pattern is discarded and
 * the incoming pattern becomes the sole active pattern (zero-overhead steady state).
 */
public class TransitionManager {

    /** Registered transitions. */
    private final Map<String, BitmapTransition> transitions = new LinkedHashMap<>();

    /** Default transition duration in ticks (20 TPS = 1 second). */
    private int defaultDurationTicks = 20;

    /** Per-zone transition state. */
    private final Map<String, TransitionState> activeTransitions = new HashMap<>();

    public TransitionManager() {
        registerBuiltIn();
    }

    private void registerBuiltIn() {
        register(new CrossfadeTransition());
        register(new DissolveTransition());
        register(new WipeTransition());
        register(new WipeTransition(WipeTransition.Direction.RIGHT, 0.08));
        register(new WipeTransition(WipeTransition.Direction.UP, 0.08));
        register(new WipeTransition(WipeTransition.Direction.DOWN, 0.08));
        register(new IrisTransition(true, 0.06));
        register(new IrisTransition(false, 0.06));
    }

    public void register(BitmapTransition transition) {
        transitions.put(transition.getId(), transition);
    }

    public BitmapTransition getTransition(String id) {
        return transitions.get(id);
    }

    public List<String> getTransitionIds() {
        return new ArrayList<>(transitions.keySet());
    }

    public void setDefaultDuration(int ticks) {
        this.defaultDurationTicks = Math.max(1, ticks);
    }

    public int getDefaultDuration() {
        return defaultDurationTicks;
    }

    /**
     * Start a transition for a zone.
     *
     * @param zoneName       zone key
     * @param fromPattern    outgoing pattern
     * @param toPattern      incoming pattern
     * @param transitionId   transition type (null = use "crossfade")
     * @param durationTicks  duration in ticks (0 = instant cut)
     * @param width          buffer width
     * @param height         buffer height
     */
    public void startTransition(String zoneName, BitmapPattern fromPattern,
                                 BitmapPattern toPattern, String transitionId,
                                 int durationTicks, int width, int height) {
        if (durationTicks <= 0) {
            // Instant cut — no transition needed
            activeTransitions.remove(zoneName);
            return;
        }

        BitmapTransition transition = transitions.get(
            transitionId != null ? transitionId : "crossfade");
        if (transition == null) {
            transition = transitions.get("crossfade");
        }
        transition.reset();

        TransitionState state = new TransitionState(
            fromPattern, toPattern, transition,
            new BitmapFrameBuffer(width, height),
            new BitmapFrameBuffer(width, height),
            durationTicks
        );
        activeTransitions.put(zoneName, state);
    }

    /**
     * Check if a zone is currently transitioning.
     */
    public boolean isTransitioning(String zoneName) {
        return activeTransitions.containsKey(zoneName);
    }

    /**
     * Tick the transition for a zone.
     * Returns the blended output buffer, or null if no transition is active.
     *
     * @param zoneName zone key
     * @param output   output buffer to write blended frame into
     * @param audio    audio state for rendering both patterns
     * @param time     elapsed time
     * @return true if transition is still active, false if completed
     */
    public boolean tick(String zoneName, BitmapFrameBuffer output,
                        AudioState audio, double time) {
        TransitionState state = activeTransitions.get(zoneName);
        if (state == null) return false;

        state.ticksElapsed++;
        double progress = Math.min(1.0, (double) state.ticksElapsed / state.durationTicks);

        // Render both patterns into their respective buffers
        state.fromBuffer.clear();
        state.toBuffer.clear();

        try {
            state.fromPattern.render(state.fromBuffer, audio, time);
        } catch (Exception ignored) {}

        try {
            state.toPattern.render(state.toBuffer, audio, time);
        } catch (Exception ignored) {}

        // Blend into output
        state.transition.blend(state.fromBuffer, state.toBuffer, output, progress);

        // Check if transition is complete
        if (progress >= 1.0) {
            state.fromPattern.reset();
            activeTransitions.remove(zoneName);
            return false; // Transition finished
        }

        return true; // Still transitioning
    }

    /**
     * Cancel any active transition for a zone.
     */
    public void cancel(String zoneName) {
        TransitionState state = activeTransitions.remove(zoneName);
        if (state != null) {
            state.fromPattern.reset();
        }
    }

    /**
     * Cancel all active transitions.
     */
    public void cancelAll() {
        for (TransitionState state : activeTransitions.values()) {
            state.fromPattern.reset();
        }
        activeTransitions.clear();
    }

    /**
     * Get the incoming pattern during a transition (the "to" pattern).
     */
    public BitmapPattern getIncomingPattern(String zoneName) {
        TransitionState state = activeTransitions.get(zoneName);
        return state != null ? state.toPattern : null;
    }

    private static class TransitionState {
        final BitmapPattern fromPattern;
        final BitmapPattern toPattern;
        final BitmapTransition transition;
        final BitmapFrameBuffer fromBuffer;
        final BitmapFrameBuffer toBuffer;
        final int durationTicks;
        int ticksElapsed = 0;

        TransitionState(BitmapPattern from, BitmapPattern to, BitmapTransition transition,
                        BitmapFrameBuffer fromBuf, BitmapFrameBuffer toBuf, int duration) {
            this.fromPattern = from;
            this.toPattern = to;
            this.transition = transition;
            this.fromBuffer = fromBuf;
            this.toBuffer = toBuf;
            this.durationTicks = duration;
        }
    }
}
