package com.audioviz.particles;

import java.util.Collections;
import java.util.List;

/**
 * Configuration for particle effects.
 * Controls intensity, limits, thresholds, and optional entity positions
 * for spawning particles at display entity centers.
 */
public class ParticleEffectConfig {
    public static final int ABSOLUTE_MAX_PARTICLES_PER_TICK = 500;
    public static final int DEFAULT_MAX_PARTICLES_PER_TICK = 100;
    public static final int MAX_PARTICLES_PER_EFFECT = 50;
    public static final long MIN_BEAT_COOLDOWN_MS = 150;

    private boolean enabled = true;
    private double intensity = 1.0;
    private int maxParticlesPerTick = DEFAULT_MAX_PARTICLES_PER_TICK;
    private double beatThreshold = 0.3;
    private double effectIntensity = 1.0;

    /**
     * Entity positions in local (0-1) zone-normalized coordinates.
     * When non-empty, effects can spawn particles at entity centers
     * instead of hardcoded zone-relative positions.
     * Each entry is {x, y, z} in 0-1 space.
     */
    private List<double[]> entityPositions = Collections.emptyList();

    public ParticleEffectConfig() {}
    public ParticleEffectConfig(double intensity) {
        this.intensity = intensity;
        this.effectIntensity = intensity;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public double getIntensity() { return intensity; }
    public void setIntensity(double intensity) { this.intensity = Math.max(0, Math.min(2.0, intensity)); }
    public double getEffectIntensity() { return effectIntensity; }
    public void setEffectIntensity(double v) { this.effectIntensity = Math.max(0, Math.min(2.0, v)); }
    public int getMaxParticlesPerTick() { return maxParticlesPerTick; }
    public void setMaxParticlesPerTick(int max) { this.maxParticlesPerTick = Math.max(1, Math.min(ABSOLUTE_MAX_PARTICLES_PER_TICK, max)); }
    public double getBeatThreshold() { return beatThreshold; }
    public void setBeatThreshold(double t) { this.beatThreshold = Math.max(0, Math.min(1.0, t)); }

    public List<double[]> getEntityPositions() { return entityPositions; }
    public void setEntityPositions(List<double[]> positions) {
        this.entityPositions = positions != null ? positions : Collections.emptyList();
    }
    public boolean hasEntityPositions() { return !entityPositions.isEmpty(); }

    public int clampParticleCount(int requested) {
        int scaled = (int)(requested * intensity * effectIntensity);
        return Math.min(scaled, MAX_PARTICLES_PER_EFFECT);
    }
}
