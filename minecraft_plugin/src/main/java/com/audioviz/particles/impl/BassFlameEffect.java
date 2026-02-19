package com.audioviz.particles.impl;

import com.audioviz.particles.ParticleEffect;
import com.audioviz.particles.ParticleEffectConfig;
import com.audioviz.particles.ParticleSpawn;
import com.audioviz.patterns.AudioState;
import org.bukkit.Particle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bass hit -> flame burst from zone base.
 * Triggers on beat when bass frequency is above threshold.
 */
public class BassFlameEffect implements ParticleEffect {

    private static final String ID = "bass_flame";
    private static final String NAME = "Bass Flames";
    private static final String CATEGORY = "beat";

    @Override
    public String getId() { return ID; }

    @Override
    public String getName() { return NAME; }

    @Override
    public String getCategory() { return CATEGORY; }

    @Override
    public boolean isTriggeredByBeat() { return true; }

    @Override
    public List<ParticleSpawn> calculate(AudioState audio, ParticleEffectConfig config) {
        // Only trigger on beats with sufficient bass
        if (!audio.isBeat()) return Collections.emptyList();
        if (audio.getBass() < config.getBeatThreshold()) return Collections.emptyList();

        List<ParticleSpawn> spawns = new ArrayList<>();

        // Calculate particle count based on beat intensity
        double intensity = audio.getBeatIntensity();
        int baseCount = config.clampParticleCount((int)(20 * intensity));

        // Main flame burst from center-bottom
        spawns.add(ParticleSpawn.builder()
            .type(Particle.FLAME)
            .position(0.5, 0.05, 0.5)
            .count(Math.min(baseCount, 30))
            .offset(0.3, 0.15, 0.3)
            .speed(0.08)
            .build());

        // Add some sparks on strong beats
        if (intensity > 0.6) {
            spawns.add(ParticleSpawn.builder()
                .type(Particle.LAVA)
                .position(0.5, 0.02, 0.5)
                .count(Math.min(5, (int)(intensity * 8)))
                .offset(0.2, 0.0, 0.2)
                .speed(0.0)
                .build());
        }

        // Add upward flame column on very strong beats
        if (intensity > 0.8) {
            spawns.add(ParticleSpawn.builder()
                .type(Particle.FLAME)
                .position(0.5, 0.3, 0.5)
                .count(15)
                .offset(0.1, 0.2, 0.1)
                .speed(0.12)
                .build());
        }

        return spawns;
    }
}
