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
 * Bass hit -> flame burst from zone base.
 * Ported from Paper: Particle.FLAME → ParticleTypes.FLAME, Particle.LAVA → ParticleTypes.LAVA.
 */
public class BassFlameEffect implements ParticleEffect {

    @Override public String getId() { return "bass_flame"; }
    @Override public String getName() { return "Bass Flames"; }
    @Override public String getCategory() { return "beat"; }
    @Override public boolean isTriggeredByBeat() { return true; }

    @Override
    public List<ParticleSpawn> calculate(AudioState audio, ParticleEffectConfig config) {
        if (!audio.isBeat()) return Collections.emptyList();
        if (audio.getBass() < config.getBeatThreshold()) return Collections.emptyList();

        List<ParticleSpawn> spawns = new ArrayList<>();
        double intensity = audio.getBeatIntensity();
        int baseCount = config.clampParticleCount((int)(20 * intensity));

        spawns.add(ParticleSpawn.builder()
            .type(ParticleTypes.FLAME)
            .position(0.5, 0.05, 0.5)
            .count(Math.min(baseCount, 30))
            .offset(0.3, 0.15, 0.3)
            .speed(0.08)
            .build());

        if (intensity > 0.6) {
            spawns.add(ParticleSpawn.builder()
                .type(ParticleTypes.LAVA)
                .position(0.5, 0.02, 0.5)
                .count(Math.min(5, (int)(intensity * 8)))
                .offset(0.2, 0.0, 0.2)
                .speed(0.0)
                .build());
        }

        if (intensity > 0.8) {
            spawns.add(ParticleSpawn.builder()
                .type(ParticleTypes.FLAME)
                .position(0.5, 0.3, 0.5)
                .count(15)
                .offset(0.1, 0.2, 0.1)
                .speed(0.12)
                .build());
        }

        return spawns;
    }
}
