package com.audioviz.particles.impl;

import com.audioviz.particles.ParticleEffect;
import com.audioviz.particles.ParticleEffectConfig;
import com.audioviz.particles.ParticleSpawn;
import com.audioviz.patterns.AudioState;
import net.minecraft.particle.DustParticleEffect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Gentle ambient mist based on overall amplitude.
 * Creates a soft atmospheric effect around the visualization zone edges.
 * Zone-level effect — ignores entity positions.
 *
 * <p>Ported from Paper: Particle.DUST + DustOptions → DustParticleEffect(int color, float scale).
 */
public class AmbientMistEffect implements ParticleEffect {

    private final Random random = new Random();

    // ARGB packed colors
    private static final int[] MIST_COLORS = {
        0xFF_B4B4FF,   // Light blue   (180, 180, 255)
        0xFF_FFC8FF,   // Pink         (255, 200, 255)
        0xFF_C8FFC8,   // Light green  (200, 255, 200)
        0xFF_FFFFC8    // Light yellow (255, 255, 200)
    };

    @Override public String getId() { return "ambient_mist"; }
    @Override public String getName() { return "Ambient Mist"; }
    @Override public String getCategory() { return "ambient"; }
    @Override public boolean isTriggeredByBeat() { return false; }

    @Override
    public List<ParticleSpawn> calculate(AudioState audio, ParticleEffectConfig config) {
        double amplitude = audio.getAmplitude();
        if (amplitude < 0.15) return Collections.emptyList();

        List<ParticleSpawn> spawns = new ArrayList<>();
        int count = config.clampParticleCount((int) (amplitude * 3));
        if (count < 1) return Collections.emptyList();

        int color = MIST_COLORS[random.nextInt(MIST_COLORS.length)];
        DustParticleEffect dust = new DustParticleEffect(color, 1.5f);

        for (int i = 0; i < count; i++) {
            double x, z;
            if (random.nextBoolean()) {
                x = random.nextBoolean() ? 0.05 + random.nextDouble() * 0.15
                                         : 0.8 + random.nextDouble() * 0.15;
                z = random.nextDouble();
            } else {
                x = random.nextDouble();
                z = random.nextBoolean() ? 0.05 + random.nextDouble() * 0.15
                                         : 0.8 + random.nextDouble() * 0.15;
            }
            double y = 0.1 + random.nextDouble() * 0.3;

            spawns.add(ParticleSpawn.builder()
                .type(dust)
                .position(x, y, z)
                .count(1)
                .offset(0.1, 0.05, 0.1)
                .speed(0.005)
                .build());
        }

        return spawns;
    }
}
