package com.audioviz.particles.impl;

import com.audioviz.particles.ParticleEffect;
import com.audioviz.particles.ParticleEffectConfig;
import com.audioviz.particles.ParticleSpawn;
import com.audioviz.patterns.AudioState;
import org.bukkit.Particle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * High frequencies -> floating musical note particles.
 * Continuous effect that spawns notes based on high frequency energy.
 */
public class HighFreqNoteEffect implements ParticleEffect {

    private static final String ID = "high_notes";
    private static final String NAME = "Musical Notes";
    private static final String CATEGORY = "ambient";

    private final Random random = new Random();

    @Override
    public String getId() { return ID; }

    @Override
    public String getName() { return NAME; }

    @Override
    public String getCategory() { return CATEGORY; }

    @Override
    public boolean isTriggeredByBeat() { return false; }

    @Override
    public List<ParticleSpawn> calculate(AudioState audio, ParticleEffectConfig config) {
        double highEnergy = audio.getHigh();

        // Only spawn when there's significant high frequency content
        if (highEnergy < 0.2) return Collections.emptyList();

        List<ParticleSpawn> spawns = new ArrayList<>();

        // Scale particle count with high frequency energy
        int count = config.clampParticleCount((int)(highEnergy * 5));
        if (count < 1) return Collections.emptyList();

        // Spawn notes at random positions within the zone
        for (int i = 0; i < count; i++) {
            double x = 0.2 + random.nextDouble() * 0.6;
            double y = 0.3 + random.nextDouble() * 0.5;
            double z = 0.2 + random.nextDouble() * 0.6;

            spawns.add(ParticleSpawn.builder()
                .type(Particle.NOTE)
                .position(x, y, z)
                .count(1)
                .offset(0.0, 0.0, 0.0)
                .speed(1.0)  // Note particle uses speed for color
                .build());
        }

        return spawns;
    }
}
