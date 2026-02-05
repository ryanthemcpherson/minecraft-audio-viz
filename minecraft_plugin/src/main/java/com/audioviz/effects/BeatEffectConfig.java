package com.audioviz.effects;

import java.util.*;

/**
 * Configuration for beat-triggered effects in a zone.
 * Maps beat types to effects and configures thresholds/cooldowns.
 */
public class BeatEffectConfig {

    private final Map<BeatType, List<BeatEffect>> effects;
    private final Map<BeatType, Float> thresholds;
    private final Map<BeatType, Long> cooldowns;
    private final Map<BeatType, Long> lastTriggerTime;

    /**
     * Create an empty, mutable BeatEffectConfig.
     */
    public BeatEffectConfig() {
        this.effects = new HashMap<>();
        this.thresholds = new HashMap<>();
        this.cooldowns = new HashMap<>();
        this.lastTriggerTime = new HashMap<>();

        // Set defaults
        thresholds.put(BeatType.BEAT, 0.4f);
        cooldowns.put(BeatType.BEAT, 150L);
    }

    private BeatEffectConfig(Builder builder) {
        this.effects = new HashMap<>(builder.effects);
        this.thresholds = new HashMap<>(builder.thresholds);
        this.cooldowns = new HashMap<>(builder.cooldowns);
        this.lastTriggerTime = new HashMap<>();
    }

    /**
     * Add an effect for a beat type.
     */
    public void addEffect(BeatType type, BeatEffect effect) {
        effects.computeIfAbsent(type, k -> new ArrayList<>()).add(effect);
    }

    /**
     * Remove an effect for a beat type.
     */
    public void removeEffect(BeatType type, BeatEffect effect) {
        List<BeatEffect> list = effects.get(type);
        if (list != null) {
            list.remove(effect);
            if (list.isEmpty()) {
                effects.remove(type);
            }
        }
    }

    /**
     * Set threshold for a beat type.
     */
    public void setThreshold(BeatType type, double threshold) {
        thresholds.put(type, (float) Math.max(0, Math.min(1, threshold)));
    }

    /**
     * Set cooldown for a beat type.
     */
    public void setCooldown(BeatType type, long cooldownMs) {
        cooldowns.put(type, Math.max(0, cooldownMs));
    }

    /**
     * Get effects for a beat type.
     */
    public List<BeatEffect> getEffects(BeatType type) {
        return effects.getOrDefault(type, Collections.emptyList());
    }

    /**
     * Get threshold for a beat type (0.0 - 1.0).
     */
    public float getThreshold(BeatType type) {
        return thresholds.getOrDefault(type, 0.5f);
    }

    /**
     * Get cooldown in milliseconds for a beat type.
     */
    public long getCooldown(BeatType type) {
        return cooldowns.getOrDefault(type, 150L);
    }

    /**
     * Check if a beat type can trigger (not on cooldown).
     */
    public boolean canTrigger(BeatType type) {
        long now = System.currentTimeMillis();
        long lastTrigger = lastTriggerTime.getOrDefault(type, 0L);
        return (now - lastTrigger) >= getCooldown(type);
    }

    /**
     * Mark a beat type as triggered.
     */
    public void markTriggered(BeatType type) {
        lastTriggerTime.put(type, System.currentTimeMillis());
    }

    /**
     * Check if any effects are configured.
     */
    public boolean hasEffects() {
        return !effects.isEmpty();
    }

    /**
     * Get all beat types with effects.
     */
    public Set<BeatType> getBeatTypes() {
        return effects.keySet();
    }

    /**
     * Builder for BeatEffectConfig.
     */
    public static class Builder {
        private final Map<BeatType, List<BeatEffect>> effects = new HashMap<>();
        private final Map<BeatType, Float> thresholds = new HashMap<>();
        private final Map<BeatType, Long> cooldowns = new HashMap<>();

        public Builder() {
            // Set default thresholds
            thresholds.put(BeatType.KICK, 0.4f);
            thresholds.put(BeatType.SNARE, 0.35f);
            thresholds.put(BeatType.HIHAT, 0.3f);
            thresholds.put(BeatType.BASS_DROP, 0.6f);
            thresholds.put(BeatType.PEAK, 0.5f);
            thresholds.put(BeatType.ANY, 0.3f);

            // Set default cooldowns (ms)
            cooldowns.put(BeatType.KICK, 150L);
            cooldowns.put(BeatType.SNARE, 200L);
            cooldowns.put(BeatType.HIHAT, 100L);
            cooldowns.put(BeatType.BASS_DROP, 2000L);
            cooldowns.put(BeatType.PEAK, 500L);
            cooldowns.put(BeatType.ANY, 100L);
        }

        public Builder addEffect(BeatType type, BeatEffect effect) {
            effects.computeIfAbsent(type, k -> new ArrayList<>()).add(effect);
            return this;
        }

        public Builder setThreshold(BeatType type, float threshold) {
            thresholds.put(type, Math.max(0, Math.min(1, threshold)));
            return this;
        }

        public Builder setCooldown(BeatType type, long cooldownMs) {
            cooldowns.put(type, Math.max(0, cooldownMs));
            return this;
        }

        public BeatEffectConfig build() {
            return new BeatEffectConfig(this);
        }
    }
}
