package com.audioviz.particles.impl;

import com.audioviz.particles.ParticleEffect;
import com.audioviz.particles.ParticleEffectConfig;
import com.audioviz.particles.ParticleSpawn;
import com.audioviz.patterns.AudioState;
import org.bukkit.Color;
import org.bukkit.Particle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Gentle ambient mist based on overall amplitude.
 * Creates a soft atmospheric effect around the visualization.
 */
public class AmbientMistEffect implements ParticleEffect {

    private static final String ID = "ambient_mist";
    private static final String NAME = "Ambient Mist";
    private static final String CATEGORY = "ambient";

    private final Random random = new Random();

    // Soft pastel colors for mist
    private static final Color[] MIST_COLORS = {
        Color.fromRGB(180, 180, 255),  // Light blue
        Color.fromRGB(255, 200, 255),  // Light pink
        Color.fromRGB(200, 255, 200),  // Light green
        Color.fromRGB(255, 255, 200)   // Light yellow
    };

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
        double amplitude = audio.getAmplitude();
        if (amplitude < 0.15) return Collections.emptyList();

        List<ParticleSpawn> spawns = new ArrayList<>();

        // Sparse mist particles
        int count = config.clampParticleCount((int)(amplitude * 3));
        if (count < 1) return Collections.emptyList();

        // Random color from palette
        Color color = MIST_COLORS[random.nextInt(MIST_COLORS.length)];
        Particle.DustOptions dustOptions = new Particle.DustOptions(color, 1.5f);

        // Spawn at random positions around zone edges
        for (int i = 0; i < count; i++) {
            double x, z;

            // Favor edges of the zone
            if (random.nextBoolean()) {
                x = random.nextBoolean() ? 0.05 + random.nextDouble() * 0.15 : 0.8 + random.nextDouble() * 0.15;
                z = random.nextDouble();
            } else {
                x = random.nextDouble();
                z = random.nextBoolean() ? 0.05 + random.nextDouble() * 0.15 : 0.8 + random.nextDouble() * 0.15;
            }

            double y = 0.1 + random.nextDouble() * 0.3;

            spawns.add(ParticleSpawn.builder()
                .type(Particle.DUST)
                .position(x, y, z)
                .count(1)
                .offset(0.1, 0.05, 0.1)
                .speed(0.005)
                .data(dustOptions)
                .build());
        }

        return spawns;
    }
}
