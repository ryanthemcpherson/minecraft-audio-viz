package com.audioviz.patterns.impl;

import com.audioviz.patterns.*;
import java.util.*;

public class DNAHelix extends VisualizationPattern {
    private double phase = 0;

    public DNAHelix() {
        super("dna", "DNA Helix", "Double helix that twists with audio");
    }

    @Override
    public List<EntityUpdate> calculate(AudioState audio) {
        List<EntityUpdate> entities = new ArrayList<>();
        int count = config.getEntityCount();
        phase += 0.05 + audio.getAmplitude() * 0.1;

        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double angle = phase + t * Math.PI * 4;
            double strand = (i % 2 == 0) ? 1 : -1;
            double x = 0.5 + 0.2 * Math.cos(angle) * strand;
            double z = 0.5 + 0.2 * Math.sin(angle) * strand;
            double y = t * 0.9 + 0.05;
            int band = i % 6;

            entities.add(EntityUpdate.builder()
                .id(i).position(x, y, z)
                .scale(calculateScale(audio, band))
                .visible(true).band(band).build());
        }
        return entities;
    }

    @Override
    public void reset() { phase = 0; }
}
