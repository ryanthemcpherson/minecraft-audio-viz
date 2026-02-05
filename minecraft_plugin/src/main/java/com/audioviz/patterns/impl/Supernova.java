package com.audioviz.patterns.impl;

import com.audioviz.patterns.*;
import java.util.*;

public class Supernova extends VisualizationPattern {
    public Supernova() {
        super("supernova", "Supernova", "Explosive burst pattern");
    }

    @Override
    public List<EntityUpdate> calculate(AudioState audio) {
        List<EntityUpdate> entities = new ArrayList<>();
        int count = config.getEntityCount();
        double burst = audio.isBeat() ? 0.4 : 0.25;

        for (int i = 0; i < count; i++) {
            double phi = Math.acos(1 - 2.0 * (i + 0.5) / count);
            double theta = Math.PI * (1 + Math.sqrt(5)) * i;
            double r = burst + audio.getBand(i % 6) * 0.15;
            double x = 0.5 + r * Math.sin(phi) * Math.cos(theta);
            double y = 0.5 + r * Math.cos(phi);
            double z = 0.5 + r * Math.sin(phi) * Math.sin(theta);

            entities.add(EntityUpdate.builder()
                .id(i).position(x, y, z)
                .scale(calculateScale(audio, i % 6))
                .visible(true).band(i % 6).build());
        }
        return entities;
    }
}
