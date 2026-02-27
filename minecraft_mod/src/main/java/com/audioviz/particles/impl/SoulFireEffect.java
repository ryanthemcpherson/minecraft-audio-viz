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
 * Bass hit -> soul fire (blue/teal flames).
 * When entity positions are available, flames burst from each entity.
 *
 * <p>Ported from Paper: Particle.SOUL_FIRE_FLAME → ParticleTypes.SOUL_FIRE_FLAME,
 * Particle.SOUL → ParticleTypes.SOUL.
 */
public class SoulFireEffect implements ParticleEffect {

    @Override public String getId() { return "soul_fire"; }
    @Override public String getName() { return "Soul Fire"; }
    @Override public String getCategory() { return "beat"; }
    @Override public boolean isTriggeredByBeat() { return true; }

    @Override
    public List<ParticleSpawn> calculate(AudioState audio, ParticleEffectConfig config) {
        if (!audio.isBeat()) return Collections.emptyList();
        if (audio.getBass() < config.getBeatThreshold()) return Collections.emptyList();

        List<ParticleSpawn> spawns = new ArrayList<>();
        double intensity = audio.getBeatIntensity();

        if (config.hasEntityPositions()) {
            // Distribute flames across entity centers
            int countPer = Math.max(1, config.clampParticleCount((int) (25 * intensity))
                / Math.max(1, config.getEntityPositions().size()));
            for (double[] pos : config.getEntityPositions()) {
                spawns.add(ParticleSpawn.builder()
                    .type(ParticleTypes.SOUL_FIRE_FLAME)
                    .position(pos[0], pos[1], pos[2])
                    .count(countPer)
                    .offset(0.03, 0.05, 0.03)
                    .speed(0.04)
                    .build());
            }
            if (intensity > 0.7) {
                // Soul wisps from random entity positions
                for (int i = 0; i < 3 && i < config.getEntityPositions().size(); i++) {
                    double[] pos = config.getEntityPositions().get(i);
                    spawns.add(ParticleSpawn.builder()
                        .type(ParticleTypes.SOUL)
                        .position(pos[0], pos[1] + 0.05, pos[2])
                        .count(1)
                        .offset(0.02, 0.03, 0.02)
                        .speed(0.02)
                        .build());
                }
            }
        } else {
            // Original: burst from zone center-bottom
            int count = config.clampParticleCount((int) (25 * intensity));
            spawns.add(ParticleSpawn.builder()
                .type(ParticleTypes.SOUL_FIRE_FLAME)
                .position(0.5, 0.05, 0.5)
                .count(count)
                .offset(0.25, 0.1, 0.25)
                .speed(0.06)
                .build());
            if (intensity > 0.7) {
                spawns.add(ParticleSpawn.builder()
                    .type(ParticleTypes.SOUL)
                    .position(0.5, 0.2, 0.5)
                    .count(3)
                    .offset(0.15, 0.1, 0.15)
                    .speed(0.02)
                    .build());
            }
        }

        return spawns;
    }
}
