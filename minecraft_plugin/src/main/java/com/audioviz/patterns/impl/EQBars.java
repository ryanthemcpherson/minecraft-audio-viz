package com.audioviz.patterns.impl;

import com.audioviz.patterns.AudioState;
import com.audioviz.patterns.EntityUpdate;
import com.audioviz.patterns.VisualizationPattern;

import java.util.ArrayList;
import java.util.List;

/**
 * Classic EQ/Equalizer visualization with 6 vertical bars.
 * Each bar represents a frequency band and grows upward based on audio level.
 * Creates the classic "bouncing bars" equalizer look.
 *
 * Layout:
 *   ██        ██
 *   ██  ██    ██
 *   ██  ██ ██ ██
 *   ██  ██ ██ ██ ██ ██
 *  BAS LOW MID HIG AIR
 */
public class EQBars extends VisualizationPattern {

    // Band names for reference (5 bands for ultra-low-latency)
    private static final String[] BAND_NAMES = {"BASS", "LOW", "MID", "HIGH", "AIR"};

    // Smoothed band values for less jitter
    private final double[] smoothedBands = new double[5];

    // Peak hold values (for peak indicators)
    private final double[] peakValues = new double[5];
    private final double peakDecay = 0.98;  // How fast peaks fall

    // Number of segments per bar (vertical resolution)
    private int segmentsPerBar = 8;

    public EQBars() {
        super("eq", "EQ Bars", "Classic equalizer with 5 vertical frequency bars");
    }

    @Override
    public List<EntityUpdate> calculate(AudioState audio) {
        List<EntityUpdate> entities = new ArrayList<>();
        int totalEntities = config.getEntityCount();

        // Calculate segments per bar based on entity count (5 bands)
        // Minimum 5 entities (1 per band), ideal is 5*8=40 for 8 segments each
        segmentsPerBar = Math.max(1, totalEntities / 5);
        int actualEntities = segmentsPerBar * 5;

        // Smooth the band values
        double smoothFactor = 0.25;  // Slightly faster for responsive feel
        for (int i = 0; i < 5; i++) {
            double target = audio.getBand(i);
            smoothedBands[i] += (target - smoothedBands[i]) * smoothFactor;

            // Update peak hold
            if (smoothedBands[i] > peakValues[i]) {
                peakValues[i] = smoothedBands[i];
            } else {
                peakValues[i] *= peakDecay;
            }
        }

        // Beat boost
        double beatBoost = audio.isBeat() ? 0.15 * config.getBeatBoost() : 0;

        // Calculate bar spacing (5 bands)
        // Bars span X from 0.1 to 0.9 (80% of width)
        double barWidth = 0.8 / 5.0;  // Width per bar
        double startX = 0.1;

        // Height scaling - bars go from Y=0.05 to Y=0.95
        double baseY = 0.05;
        double maxHeight = 0.85;
        double segmentHeight = maxHeight / segmentsPerBar;

        int entityIndex = 0;

        for (int band = 0; band < 5; band++) {
            // X position for this bar (centered in its slot)
            double barX = startX + (band + 0.5) * barWidth;

            // How many segments should be "lit" for this band
            double bandLevel = smoothedBands[band] + beatBoost;
            bandLevel = Math.min(1.0, Math.max(0.0, bandLevel));
            int litSegments = (int) Math.ceil(bandLevel * segmentsPerBar);

            // Peak segment position
            int peakSegment = (int) Math.ceil(peakValues[band] * segmentsPerBar) - 1;
            peakSegment = Math.max(0, Math.min(segmentsPerBar - 1, peakSegment));

            for (int seg = 0; seg < segmentsPerBar; seg++) {
                if (entityIndex >= totalEntities) break;

                // Y position for this segment
                double segY = baseY + (seg + 0.5) * segmentHeight;

                // Determine visibility and scale
                boolean isLit = seg < litSegments;
                boolean isPeak = seg == peakSegment && peakSegment > litSegments - 2;

                double scale;
                if (isLit) {
                    // Lit segment - full size with slight variation by height
                    scale = calculateScale(audio, band);
                    // Slightly smaller at top for visual interest
                    scale *= 1.0 - (seg * 0.02);
                } else if (isPeak) {
                    // Peak indicator - smaller
                    scale = calculateScale(audio, band) * 0.6;
                } else {
                    // Unlit segment - very small or hidden
                    scale = 0.02;
                }

                entities.add(EntityUpdate.builder()
                    .id(entityIndex)
                    .position(barX, segY, 0.5)  // Z centered
                    .scale(scale)
                    .visible(isLit || isPeak)
                    .band(band)
                    .build());

                entityIndex++;
            }
        }

        // Fill remaining entities (if any) as hidden
        while (entityIndex < totalEntities) {
            entities.add(EntityUpdate.builder()
                .id(entityIndex)
                .position(0.5, 0.5, 0.5)
                .scale(0.01)
                .visible(false)
                .band(0)
                .build());
            entityIndex++;
        }

        update(0.016);
        return entities;
    }

    /**
     * Get the recommended entity count for optimal display.
     * 6 bars × 8 segments = 48 entities for good resolution.
     */
    public static int getRecommendedEntityCount() {
        return 48;
    }
}
