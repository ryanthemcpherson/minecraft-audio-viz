package com.audioviz.particles.impl;

import com.audioviz.particles.ParticleEffect;
import com.audioviz.particles.ParticleEffectConfig;
import com.audioviz.particles.ParticleSpawn;
import com.audioviz.patterns.AudioState;
import net.minecraft.particle.DustParticleEffect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Colored dust particles matching EQ bands.
 * Each frequency band gets a different color, creating a rainbow effect.
 * When entity positions are available, dust spawns at entity centers
 * colored by the entity's band assignment.
 *
 * <p>Ported from Paper: Particle.DUST + DustOptions → DustParticleEffect(int color, float scale).
 */
public class SpectrumDustEffect implements ParticleEffect {

    // ARGB packed colors for each frequency band (5 bands)
    private static final int[] BAND_COLORS = {
        0xFF_FF8000,     // Bass: Orange    (255, 128, 0)
        0xFF_FFFF00,     // Low-mid: Yellow (255, 255, 0)
        0xFF_00FF00,     // Mid: Green      (0, 255, 0)
        0xFF_0080FF,     // Upper-mid: Cyan (0, 128, 255)
        0xFF_FF00FF      // High: Magenta   (255, 0, 255)
    };

    // X positions for each band (spread across zone)
    private static final double[] BAND_X = {0.17, 0.35, 0.52, 0.69, 0.83};

    @Override public String getId() { return "spectrum_dust"; }
    @Override public String getName() { return "Spectrum Dust"; }
    @Override public String getCategory() { return "ambient"; }
    @Override public boolean isTriggeredByBeat() { return false; }

    @Override
    public List<ParticleSpawn> calculate(AudioState audio, ParticleEffectConfig config) {
        double amplitude = audio.getAmplitude();
        if (amplitude < 0.1) return Collections.emptyList();

        List<ParticleSpawn> spawns = new ArrayList<>();

        if (config.hasEntityPositions()) {
            // Spawn colored dust at entity centers, coloring by position-derived band
            List<double[]> positions = config.getEntityPositions();
            for (int i = 0; i < positions.size(); i++) {
                double[] pos = positions.get(i);
                // Assign band based on entity's X position within zone (0-1)
                int band = Math.min(4, (int) (pos[0] * 5));
                double bandValue = audio.getBand(band);
                if (bandValue < 0.15) continue;

                float size = (float) (0.8 + bandValue * 0.5);
                DustParticleEffect dust = new DustParticleEffect(BAND_COLORS[band], size);

                spawns.add(ParticleSpawn.builder()
                    .type(dust)
                    .position(pos[0], pos[1], pos[2])
                    .count(1)
                    .offset(0.02, 0.03, 0.02)
                    .speed(0.01)
                    .build());
            }
        } else {
            // Original: bands spread across zone width
            for (int band = 0; band < 5; band++) {
                double bandValue = audio.getBand(band);
                if (bandValue < 0.15) continue;

                int count = config.clampParticleCount((int) (bandValue * 4));
                if (count < 1) continue;

                double y = 0.1 + bandValue * 0.6;
                float size = (float) (0.8 + bandValue * 0.5);
                DustParticleEffect dust = new DustParticleEffect(BAND_COLORS[band], size);

                spawns.add(ParticleSpawn.builder()
                    .type(dust)
                    .position(BAND_X[band], y, 0.5)
                    .count(count)
                    .offset(0.08, 0.1, 0.15)
                    .speed(0.01)
                    .build());
            }
        }

        return spawns;
    }
}
