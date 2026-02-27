package com.audioviz.bitmap.transitions;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages pattern transitions with double-buffering.
 *
 * <p>When a transition is triggered, both the outgoing and incoming patterns
 * render into separate buffers each tick. The active {@link BitmapTransition}
 * blends them into the output buffer based on elapsed progress.
 */
public class TransitionManager {

    private final Map<String, BitmapTransition> transitions = new LinkedHashMap<>();
    private int defaultDurationTicks = 20;
    private final Map<String, TransitionState> activeTransitions = new ConcurrentHashMap<>();

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
        register(new ChannelChangeTransition());
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

    public void startTransition(String zoneName, BitmapPattern fromPattern,
                                 BitmapPattern toPattern, String transitionId,
                                 int durationTicks, int width, int height) {
        if (durationTicks <= 0) {
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

    public boolean isTransitioning(String zoneName) {
        return activeTransitions.containsKey(zoneName);
    }

    public boolean tick(String zoneName, BitmapFrameBuffer output,
                        AudioState audio, double time) {
        TransitionState state = activeTransitions.get(zoneName);
        if (state == null) return false;

        state.ticksElapsed++;
        double progress = Math.min(1.0, (double) state.ticksElapsed / state.durationTicks);

        state.fromBuffer.clear();
        state.toBuffer.clear();

        try {
            state.fromPattern.render(state.fromBuffer, audio, time);
        } catch (Exception ignored) {}

        try {
            state.toPattern.render(state.toBuffer, audio, time);
        } catch (Exception ignored) {}

        state.transition.blend(state.fromBuffer, state.toBuffer, output, progress);

        if (progress >= 1.0) {
            state.fromPattern.reset();
            activeTransitions.remove(zoneName);
            return false;
        }

        return true;
    }

    public void cancel(String zoneName) {
        TransitionState state = activeTransitions.remove(zoneName);
        if (state != null) {
            state.fromPattern.reset();
        }
    }

    public void cancelAll() {
        for (TransitionState state : activeTransitions.values()) {
            state.fromPattern.reset();
        }
        activeTransitions.clear();
    }

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
