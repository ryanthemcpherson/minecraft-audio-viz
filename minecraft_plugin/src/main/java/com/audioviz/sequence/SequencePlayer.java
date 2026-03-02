package com.audioviz.sequence;

import java.util.*;

/**
 * Plays through a Sequence's steps, tracking duration and advancing.
 * Pure logic — no Bukkit dependencies. Call tick() once per server tick.
 */
public class SequencePlayer {

    private final Sequence sequence;
    private int currentStepIndex;
    private int ticksInCurrentStep;
    private boolean finished;

    // Shuffle state
    private final List<Integer> shuffleOrder = new ArrayList<>();
    private int shufflePosition = 0;
    private final Random random = new Random();

    public SequencePlayer(Sequence sequence) {
        this.sequence = sequence;
        this.currentStepIndex = 0;
        this.ticksInCurrentStep = 0;
        this.finished = sequence.getSteps().isEmpty();

        if (sequence.getMode() == PlaybackMode.SHUFFLE && !sequence.getSteps().isEmpty()) {
            buildShuffleOrder(-1);
            this.currentStepIndex = shuffleOrder.get(0);
        }
    }

    /**
     * Advance one tick. Returns a StepTransition if the step changed, null otherwise.
     */
    public StepTransition tick() {
        if (finished) return null;

        ticksInCurrentStep++;
        SequenceStep currentStep = sequence.getSteps().get(currentStepIndex);
        int duration = sequence.getEffectiveDuration(currentStep);

        if (ticksInCurrentStep >= duration) {
            return advance();
        }
        return null;
    }

    /**
     * Skip to next step immediately.
     */
    public StepTransition skip() {
        if (finished) return null;
        return advance();
    }

    private StepTransition advance() {
        int nextIndex = computeNextIndex();
        if (nextIndex < 0) {
            finished = true;
            return null;
        }

        currentStepIndex = nextIndex;
        ticksInCurrentStep = 0;

        SequenceStep step = sequence.getSteps().get(currentStepIndex);
        return new StepTransition(
            step.zonePatterns(),
            sequence.getEffectiveTransition(step),
            sequence.getEffectiveTransitionDuration(step)
        );
    }

    private int computeNextIndex() {
        int stepCount = sequence.getSteps().size();
        return switch (sequence.getMode()) {
            case LOOP -> (currentStepIndex + 1) % stepCount;
            case ONCE -> {
                int next = currentStepIndex + 1;
                yield next < stepCount ? next : -1;
            }
            case SHUFFLE -> {
                shufflePosition++;
                if (shufflePosition >= shuffleOrder.size()) {
                    buildShuffleOrder(currentStepIndex);
                    shufflePosition = 0;
                }
                yield shuffleOrder.get(shufflePosition);
            }
        };
    }

    private void buildShuffleOrder(int lastIndex) {
        shuffleOrder.clear();
        for (int i = 0; i < sequence.getSteps().size(); i++) {
            shuffleOrder.add(i);
        }
        Collections.shuffle(shuffleOrder, random);
        // Avoid immediate repeat of last step
        if (lastIndex >= 0 && !shuffleOrder.isEmpty() && shuffleOrder.get(0) == lastIndex) {
            if (shuffleOrder.size() > 1) {
                int swapIdx = 1 + random.nextInt(shuffleOrder.size() - 1);
                Collections.swap(shuffleOrder, 0, swapIdx);
            }
        }
    }

    public int getCurrentStepIndex() { return currentStepIndex; }
    public boolean isFinished() { return finished; }
    public Sequence getSequence() { return sequence; }
    public int getTicksInCurrentStep() { return ticksInCurrentStep; }

    /**
     * Returned when a step transition occurs.
     */
    public record StepTransition(
        Map<String, String> zonePatterns,
        String transitionId,
        int transitionDuration
    ) {}
}
