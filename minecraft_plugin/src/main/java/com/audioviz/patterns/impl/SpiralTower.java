package com.audioviz.patterns.impl;

import com.audioviz.patterns.*;
import java.util.*;

public class SpiralTower extends VisualizationPattern {
    private double angle = 0;

    public SpiralTower() {
        super("spiral", "Spiral Tower", "Entities spiral upward in a helix pattern");
    }

    @Override
    public List<EntityUpdate> calculate(AudioState audio) {
        List<EntityUpdate> entities = new ArrayList<>();
        int count = config.getEntityCount();
        angle += audio.getAmplitude() * 0.1;

        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double a = angle + t * Math.PI * 4;
            double r = 0.3;
            double x = 0.5 + r * Math.cos(a);
            double z = 0.5 + r * Math.sin(a);
            double y = t * 0.9 + 0.05;
            int band = i % 6;

            entities.add(EntityUpdate.builder()
                .id(i)
                .position(x, y, z)
                .scale(calculateScale(audio, band))
                .visible(true)
                .band(band)
                .build());
        }
        return entities;
    }

    @Override
    public void reset() { angle = 0; }
}
