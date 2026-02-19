package com.audioviz.patterns.impl;

import com.audioviz.patterns.*;
import java.util.*;

public class WaveRing extends VisualizationPattern {
    private double phase = 0;

    public WaveRing() {
        super("wave", "Wave Ring", "Circular wave pattern");
    }

    @Override
    public List<EntityUpdate> calculate(AudioState audio) {
        List<EntityUpdate> entities = new ArrayList<>();
        int count = config.getEntityCount();
        phase += 0.1;

        for (int i = 0; i < count; i++) {
            double angle = (2 * Math.PI * i) / count;
            double r = 0.3;
            double wave = Math.sin(phase + angle * 3) * audio.getAmplitude() * 0.1;
            double x = 0.5 + (r + wave) * Math.cos(angle);
            double z = 0.5 + (r + wave) * Math.sin(angle);
            double y = 0.5 + audio.getBand(i % 6) * 0.3;

            entities.add(EntityUpdate.builder()
                .id(i).position(x, y, z)
                .scale(calculateScale(audio, i % 6))
                .visible(true).band(i % 6).build());
        }
        return entities;
    }

    @Override
    public void reset() { phase = 0; }
}
