package com.audioviz.patterns.impl;

import com.audioviz.patterns.*;
import java.util.*;

public class PulseSphere extends VisualizationPattern {
    public PulseSphere() {
        super("sphere", "Pulse Sphere", "Sphere that pulses with audio");
    }

    @Override
    public List<EntityUpdate> calculate(AudioState audio) {
        List<EntityUpdate> entities = new ArrayList<>();
        int count = config.getEntityCount();
        double radius = 0.3 + audio.getAmplitude() * 0.15;

        for (int i = 0; i < count; i++) {
            double phi = Math.acos(1 - 2.0 * (i + 0.5) / count);
            double theta = Math.PI * (1 + Math.sqrt(5)) * i;
            double x = 0.5 + radius * Math.sin(phi) * Math.cos(theta);
            double y = 0.5 + radius * Math.cos(phi);
            double z = 0.5 + radius * Math.sin(phi) * Math.sin(theta);
            int band = i % 6;

            entities.add(EntityUpdate.builder()
                .id(i).position(x, y, z)
                .scale(calculateScale(audio, band))
                .visible(true).band(band).build());
        }
        return entities;
    }
}
