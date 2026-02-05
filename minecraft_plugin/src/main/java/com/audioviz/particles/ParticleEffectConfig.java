package com.audioviz.particles;

/**
 * Configuration for particle effects.
 * Controls intensity, limits, and thresholds.
 */
public class ParticleEffectConfig {

    // Global limits
    public static final int ABSOLUTE_MAX_PARTICLES_PER_TICK = 500;
    public static final int DEFAULT_MAX_PARTICLES_PER_TICK = 100;
    public static final int MAX_PARTICLES_PER_EFFECT = 50;

    // Timing
    public static final long MIN_BEAT_COOLDOWN_MS = 150;

    private boolean enabled = true;
    private double intensity = 1.0;
    private int maxParticlesPerTick = DEFAULT_MAX_PARTICLES_PER_TICK;
    private double beatThreshold = 0.3;

    // Per-effect settings
    private double effectIntensity = 1.0;

    public ParticleEffectConfig() {}

    public ParticleEffectConfig(double intensity) {
        this.intensity = intensity;
        this.effectIntensity = intensity;
    }

    // Getters and setters
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getIntensity() { return intensity; }
    public void setIntensity(double intensity) {
        this.intensity = Math.max(0, Math.min(2.0, intensity));
    }

    public double getEffectIntensity() { return effectIntensity; }
    public void setEffectIntensity(double effectIntensity) {
        this.effectIntensity = Math.max(0, Math.min(2.0, effectIntensity));
    }

    public int getMaxParticlesPerTick() { return maxParticlesPerTick; }
    public void setMaxParticlesPerTick(int max) {
        this.maxParticlesPerTick = Math.max(1, Math.min(ABSOLUTE_MAX_PARTICLES_PER_TICK, max));
    }

    public double getBeatThreshold() { return beatThreshold; }
    public void setBeatThreshold(double threshold) {
        this.beatThreshold = Math.max(0, Math.min(1.0, threshold));
    }

    /**
     * Calculate effective particle count with limits applied.
     */
    public int clampParticleCount(int requested) {
        int scaled = (int)(requested * intensity * effectIntensity);
        return Math.min(scaled, MAX_PARTICLES_PER_EFFECT);
    }
}
