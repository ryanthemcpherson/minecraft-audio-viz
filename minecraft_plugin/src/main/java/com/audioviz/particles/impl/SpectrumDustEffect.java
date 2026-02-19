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

/**
 * Colored dust particles matching EQ bands.
 * Each frequency band gets a different color, creating a rainbow effect.
 */
public class SpectrumDustEffect implements ParticleEffect {

    private static final String ID = "spectrum_dust";
    private static final String NAME = "Spectrum Dust";
    private static final String CATEGORY = "ambient";

    // Colors for each frequency band (5 bands for ultra-low-latency)
    private static final Color[] BAND_COLORS = {
        Color.fromRGB(255, 128, 0),    // Bass: Orange (includes kick)
        Color.fromRGB(255, 255, 0),    // Low-mid: Yellow
        Color.fromRGB(0, 255, 0),      // Mid: Green
        Color.fromRGB(0, 128, 255),    // Upper-mid: Cyan
        Color.fromRGB(255, 0, 255)     // High: Magenta
    };

    // X positions for each band (spread across zone) - 5 bands
    private static final double[] BAND_X = {0.17, 0.35, 0.52, 0.69, 0.83};

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
        if (amplitude < 0.1) return Collections.emptyList();

        List<ParticleSpawn> spawns = new ArrayList<>();

        // Create dust for each band (5 bands for ultra-low-latency)
        for (int band = 0; band < 5; band++) {
            double bandValue = audio.getBand(band);
            if (bandValue < 0.15) continue;

            int count = config.clampParticleCount((int)(bandValue * 4));
            if (count < 1) continue;

            // Height based on band energy
            double y = 0.1 + bandValue * 0.6;

            // Create colored dust
            Particle.DustOptions dustOptions = new Particle.DustOptions(
                BAND_COLORS[band],
                (float)(0.8 + bandValue * 0.5)  // Size 0.8-1.3
            );

            spawns.add(ParticleSpawn.builder()
                .type(Particle.DUST)
                .position(BAND_X[band], y, 0.5)
                .count(count)
                .offset(0.08, 0.1, 0.15)
                .speed(0.01)
                .data(dustOptions)
                .build());
        }

        return spawns;
    }
}
