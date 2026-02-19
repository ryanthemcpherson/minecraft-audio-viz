package com.audioviz.patterns.impl;

import com.audioviz.patterns.*;
import java.util.*;

/**
 * Spectrum bars visualization - vertical bars that bounce with frequency bands.
 */
public class SpectrumBars extends VisualizationPattern {

    public SpectrumBars() {
        super("spectrum", "Spectrum Bars", "Vertical bars that bounce with frequency bands");
    }

    @Override
    public List<EntityUpdate> calculate(AudioState audio) {
        List<EntityUpdate> entities = new ArrayList<>();
        int count = config.getEntityCount();

        for (int i = 0; i < count; i++) {
            int band = i % 6;
            double x = (i + 0.5) / count;
            double y = audio.getBand(band) * 0.8 + 0.1;
            double scale = calculateScale(audio, band);

            entities.add(EntityUpdate.builder()
                .id(i)
                .position(x, y, 0.5)
                .scale(scale)
                .visible(true)
                .band(band)
                .build());
        }
        return entities;
    }
}
