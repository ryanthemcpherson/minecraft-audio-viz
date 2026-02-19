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
 * Bass hit -> soul fire (blue/teal flames).
 * Alternative to regular flames with a cooler aesthetic.
 */
public class SoulFireEffect implements ParticleEffect {

    private static final String ID = "soul_fire";
    private static final String NAME = "Soul Fire";
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
        if (audio.getSubBass() < config.getBeatThreshold()) return Collections.emptyList();

        List<ParticleSpawn> spawns = new ArrayList<>();

        double intensity = audio.getBeatIntensity();
        int count = config.clampParticleCount((int)(25 * intensity));

        // Soul fire burst
        spawns.add(ParticleSpawn.builder()
            .type(Particle.SOUL_FIRE_FLAME)
            .position(0.5, 0.05, 0.5)
            .count(count)
            .offset(0.25, 0.1, 0.25)
            .speed(0.06)
            .build());

        // Add soul particles on strong beats
        if (intensity > 0.7) {
            spawns.add(ParticleSpawn.builder()
                .type(Particle.SOUL)
                .position(0.5, 0.2, 0.5)
                .count(3)
                .offset(0.15, 0.1, 0.15)
                .speed(0.02)
                .build());
        }

        return spawns;
    }
}
