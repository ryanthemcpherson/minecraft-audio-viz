package com.audioviz.particles.impl;

import com.audioviz.particles.ParticleEffect;
import com.audioviz.particles.ParticleEffectConfig;
import com.audioviz.particles.ParticleSpawn;
import com.audioviz.patterns.AudioState;
import net.minecraft.particle.ParticleTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * High frequencies -> floating musical note particles.
 * When entity positions are available, notes float up from entity centers.
 *
 * <p>Ported from Paper: Particle.NOTE → ParticleTypes.NOTE.
 */
public class HighFreqNoteEffect implements ParticleEffect {

    private final Random random = new Random();

    @Override public String getId() { return "high_notes"; }
    @Override public String getName() { return "Musical Notes"; }
    @Override public String getCategory() { return "ambient"; }
    @Override public boolean isTriggeredByBeat() { return false; }

    @Override
    public List<ParticleSpawn> calculate(AudioState audio, ParticleEffectConfig config) {
        double highEnergy = audio.getHigh();
        if (highEnergy < 0.2) return Collections.emptyList();

        List<ParticleSpawn> spawns = new ArrayList<>();
        int count = config.clampParticleCount((int) (highEnergy * 5));
        if (count < 1) return Collections.emptyList();

        if (config.hasEntityPositions()) {
            // Spawn notes rising from random entity positions
            List<double[]> positions = config.getEntityPositions();
            for (int i = 0; i < count; i++) {
                double[] pos = positions.get(random.nextInt(positions.size()));
                spawns.add(ParticleSpawn.builder()
                    .type(ParticleTypes.NOTE)
                    .position(pos[0], pos[1] + 0.05, pos[2])
                    .count(1)
                    .offset(0.0, 0.0, 0.0)
                    .speed(1.0)
                    .build());
            }
        } else {
            // Original: random positions within zone interior
            for (int i = 0; i < count; i++) {
                double x = 0.2 + random.nextDouble() * 0.6;
                double y = 0.3 + random.nextDouble() * 0.5;
                double z = 0.2 + random.nextDouble() * 0.6;
                spawns.add(ParticleSpawn.builder()
                    .type(ParticleTypes.NOTE)
                    .position(x, y, z)
                    .count(1)
                    .offset(0.0, 0.0, 0.0)
                    .speed(1.0)
                    .build());
            }
        }

        return spawns;
    }
}
