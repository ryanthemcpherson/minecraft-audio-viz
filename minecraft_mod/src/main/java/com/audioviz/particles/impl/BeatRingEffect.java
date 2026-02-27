package com.audioviz.particles.impl;

import com.audioviz.particles.ParticleEffect;
import com.audioviz.particles.ParticleEffectConfig;
import com.audioviz.particles.ParticleSpawn;
import com.audioviz.patterns.AudioState;
import net.minecraft.particle.ParticleTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Beat -> expanding ring of END_ROD particles.
 * When entity positions are available, spawns rings centered on each entity.
 *
 * <p>Ported from Paper: Particle.END_ROD → ParticleTypes.END_ROD.
 */
public class BeatRingEffect implements ParticleEffect {

    @Override public String getId() { return "beat_ring"; }
    @Override public String getName() { return "Beat Ring"; }
    @Override public String getCategory() { return "beat"; }
    @Override public boolean isTriggeredByBeat() { return true; }

    @Override
    public List<ParticleSpawn> calculate(AudioState audio, ParticleEffectConfig config) {
        if (!audio.isBeat()) return Collections.emptyList();

        List<ParticleSpawn> spawns = new ArrayList<>();
        double intensity = audio.getBeatIntensity();
        double radius = 0.3;

        if (config.hasEntityPositions()) {
            // Spawn mini rings at each entity center
            int pointsPer = Math.max(4, config.clampParticleCount((int) (8 * intensity))
                / Math.max(1, config.getEntityPositions().size()));
            for (double[] pos : config.getEntityPositions()) {
                for (int i = 0; i < pointsPer; i++) {
                    double angle = (2 * Math.PI * i) / pointsPer;
                    double x = pos[0] + 0.02 * Math.cos(angle);
                    double z = pos[2] + 0.02 * Math.sin(angle);
                    spawns.add(ParticleSpawn.builder()
                        .type(ParticleTypes.END_ROD)
                        .position(x, pos[1], z)
                        .count(1)
                        .offset(0.01, 0.01, 0.01)
                        .speed(0.02)
                        .build());
                }
            }
        } else {
            // Original: ring at zone center
            int ringPoints = config.clampParticleCount((int) (16 * intensity));
            for (int i = 0; i < ringPoints; i++) {
                double angle = (2 * Math.PI * i) / ringPoints;
                double x = 0.5 + radius * Math.cos(angle);
                double z = 0.5 + radius * Math.sin(angle);
                spawns.add(ParticleSpawn.builder()
                    .type(ParticleTypes.END_ROD)
                    .position(x, 0.3, z)
                    .count(1)
                    .offset(0.02, 0.02, 0.02)
                    .speed(0.02)
                    .build());
            }
            if (intensity > 0.7) {
                spawns.add(ParticleSpawn.builder()
                    .type(ParticleTypes.END_ROD)
                    .position(0.5, 0.3, 0.5)
                    .count(8)
                    .offset(0.1, 0.05, 0.1)
                    .speed(0.05)
                    .build());
            }
        }

        return spawns;
    }
}
