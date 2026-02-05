package com.audioviz.particles;

import com.audioviz.patterns.AudioState;

import java.util.List;

/**
 * Interface for audio-reactive particle effects.
 * Effects calculate particle spawns based on audio state.
 */
public interface ParticleEffect {

    /**
     * Unique identifier for this effect.
     */
    String getId();

    /**
     * Display name for UI.
     */
    String getName();

    /**
     * Category for grouping in UI (e.g., "beat", "ambient").
     */
    String getCategory();

    /**
     * Whether this effect triggers on beats (burst) or runs continuously.
     */
    boolean isTriggeredByBeat();

    /**
     * Calculate particle spawns based on current audio state.
     *
     * @param audio Current audio state (bands, beat, intensity)
     * @param config Effect configuration (intensity, thresholds)
     * @return List of particles to spawn (may be empty)
     */
    List<ParticleSpawn> calculate(AudioState audio, ParticleEffectConfig config);
}
