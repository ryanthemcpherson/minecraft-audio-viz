package com.audioviz.sequence;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered list of steps that rotate patterns across zones.
 */
public class Sequence {

    private final String name;
    private final List<SequenceStep> steps = new ArrayList<>();
    private PlaybackMode mode = PlaybackMode.LOOP;
    private int defaultStepDuration = 600;
    private String defaultTransition = "crossfade";
    private int defaultTransitionDuration = 20;

    public Sequence(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public List<SequenceStep> getSteps() { return steps; }
    public PlaybackMode getMode() { return mode; }
    public int getDefaultStepDuration() { return defaultStepDuration; }
    public String getDefaultTransition() { return defaultTransition; }
    public int getDefaultTransitionDuration() { return defaultTransitionDuration; }

    public void setMode(PlaybackMode mode) { this.mode = mode; }
    public void setDefaultStepDuration(int ticks) { this.defaultStepDuration = Math.max(1, ticks); }
    public void setDefaultTransition(String id) { this.defaultTransition = id; }
    public void setDefaultTransitionDuration(int ticks) { this.defaultTransitionDuration = Math.max(1, ticks); }

    public void addStep(SequenceStep step) { steps.add(step); }
    public void removeStep(int index) { steps.remove(index); }

    public int getEffectiveDuration(SequenceStep step) {
        return step.durationTicks() > 0 ? step.durationTicks() : defaultStepDuration;
    }

    public String getEffectiveTransition(SequenceStep step) {
        return step.transitionId() != null ? step.transitionId() : defaultTransition;
    }

    public int getEffectiveTransitionDuration(SequenceStep step) {
        return step.transitionDuration() > 0 ? step.transitionDuration() : defaultTransitionDuration;
    }
}
