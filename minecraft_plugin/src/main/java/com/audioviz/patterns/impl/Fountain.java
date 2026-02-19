package com.audioviz.patterns.impl;

import com.audioviz.patterns.*;
import java.util.*;

public class Fountain extends VisualizationPattern {
    private double time = 0;

    public Fountain() {
        super("fountain", "Fountain", "Upward flowing fountain effect");
    }

    @Override
    public List<EntityUpdate> calculate(AudioState audio) {
        List<EntityUpdate> entities = new ArrayList<>();
        int count = config.getEntityCount();
        time += 0.05;

        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double phase = time + t * 2;
            double r = 0.1 + t * 0.2;
            double angle = phase * 3;
            double x = 0.5 + r * Math.cos(angle);
            double z = 0.5 + r * Math.sin(angle);
            double y = (t + Math.sin(phase) * 0.1) * 0.8 + 0.1;

            entities.add(EntityUpdate.builder()
                .id(i).position(x, y, z)
                .scale(calculateScale(audio, i % 6))
                .visible(true).band(i % 6).build());
        }
        return entities;
    }

    @Override
    public void reset() { time = 0; }
}
