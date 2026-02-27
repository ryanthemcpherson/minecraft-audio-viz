package com.audioviz.particles;

import com.audioviz.patterns.AudioState;
import java.util.List;

/**
 * Interface for audio-reactive particle effects.
 * Direct port from Paper — no API dependencies.
 */
public interface ParticleEffect {
    String getId();
    String getName();
    String getCategory();
    boolean isTriggeredByBeat();
    List<ParticleSpawn> calculate(AudioState audio, ParticleEffectConfig config);
}
