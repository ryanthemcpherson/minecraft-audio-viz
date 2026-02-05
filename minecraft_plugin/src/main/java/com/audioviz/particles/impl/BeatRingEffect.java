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
 * Beat -> expanding ring of END_ROD particles.
 * Creates a ring that expands outward on each beat.
 */
public class BeatRingEffect implements ParticleEffect {

    private static final String ID = "beat_ring";
    private static final String NAME = "Beat Ring";
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
        if (!audio.isBeat()) return Collections.emptyList();

        List<ParticleSpawn> spawns = new ArrayList<>();

        double intensity = audio.getBeatIntensity();
        int ringPoints = config.clampParticleCount((int)(16 * intensity));
        double radius = 0.3;  // Ring radius in local coords

        // Create ring of particles at zone center height
        for (int i = 0; i < ringPoints; i++) {
            double angle = (2 * Math.PI * i) / ringPoints;
            double x = 0.5 + radius * Math.cos(angle);
            double z = 0.5 + radius * Math.sin(angle);

            spawns.add(ParticleSpawn.builder()
                .type(Particle.END_ROD)
                .position(x, 0.3, z)
                .count(1)
                .offset(0.02, 0.02, 0.02)
                .speed(0.02)
                .build());
        }

        // Add center burst on strong beats
        if (intensity > 0.7) {
            spawns.add(ParticleSpawn.builder()
                .type(Particle.END_ROD)
                .position(0.5, 0.3, 0.5)
                .count(8)
                .offset(0.1, 0.05, 0.1)
                .speed(0.05)
                .build());
        }

        return spawns;
    }
}
