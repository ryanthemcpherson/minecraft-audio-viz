package com.audioviz.decorators.impl;

import com.audioviz.AudioVizPlugin;
import com.audioviz.decorators.DJInfo;
import com.audioviz.decorators.DecoratorConfig;
import com.audioviz.decorators.StageDecorator;
import com.audioviz.entities.EntityUpdate;
import com.audioviz.patterns.AudioState;
import com.audioviz.stages.Stage;
import org.bukkit.Location;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Multiple floating TextDisplay entities that pulse, change color, and glow on beats.
 * Shows hype phrases on strong beats.
 */
public class BeatTextFXDecorator extends StageDecorator {

    private static final String[] DEFAULT_PHRASES = {"DROP", "BASS", "FIRE", "VIBE"};
    private static final String[] BAND_COLORS = {
        "\u00A7c", "\u00A76", "\u00A7e", "\u00A7a", "\u00A7b"
    };

    private int[] glowCountdowns;

    public BeatTextFXDecorator(Stage stage, AudioVizPlugin plugin) {
        super("text_fx", "Beat Text FX", stage, plugin);
    }

    @Override
    public DecoratorConfig getDefaultConfig() {
        DecoratorConfig config = new DecoratorConfig();
        config.set("text_count", 3);
        config.set("scale_min", 0.8);
        config.set("scale_max", 1.5);
        config.set("glow_threshold", 0.7);
        config.set("height_offset", 4.0);
        config.set("spread_radius", 6.0);
        return config;
    }

    @Override
    public void onActivate() {
        int count = config.getInt("text_count", 3);
        double heightOffset = config.getDouble("height_offset", 4.0);
        double radius = config.getDouble("spread_radius", 6.0);

        // Position the zone at stage anchor + height offset, behind the main stage
        Location origin = getStageRelativeLocation(-radius, heightOffset, -radius);
        createDecoratorZone(origin, new Vector(radius * 2, 2, radius * 2));
        initTextPool(count);

        glowCountdowns = new int[count];

        // Initial text placement in a semicircle
        String zoneName = getDecoratorZoneName();
        List<EntityUpdate> updates = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * (0.3 + 0.4 * i / Math.max(1, count - 1));
            double x = radius + radius * Math.cos(angle);
            double z = radius + radius * Math.sin(angle);

            Location pos = origin.clone().add(x, 0, z);
            updates.add(EntityUpdate.builder("text_" + i)
                .location(pos)
                .build());

            // Set initial text
            plugin.getEntityPoolManager().updateTextContent(zoneName, "text_" + i,
                "\u00A78\u00A7l" + getPhrase(i));
        }
        plugin.getEntityPoolManager().batchUpdateEntities(zoneName, updates);
    }

    @Override
    public void onDeactivate() {
        cleanupDecoratorZone();
    }

    @Override
    public void tick(AudioState audio, DJInfo djInfo) {
        int count = config.getInt("text_count", 3);
        float scaleMin = config.getFloat("scale_min", 0.8f);
        float scaleMax = config.getFloat("scale_max", 1.5f);
        double glowThreshold = config.getDouble("glow_threshold", 0.7);

        String zoneName = getDecoratorZoneName();
        List<EntityUpdate> updates = new ArrayList<>();

        int dominant = getDominantBand(audio);

        for (int i = 0; i < count && i < glowCountdowns.length; i++) {
            // Scale oscillation based on mid-range
            float baseScale = (float) lerp(scaleMin, scaleMax * 0.8, audio.getMid());

            boolean shouldGlow = false;

            // Strong beat effects
            if (audio.isBeat() && audio.getBeatIntensity() > glowThreshold) {
                baseScale = scaleMax;
                shouldGlow = true;
                glowCountdowns[i] = 3;

                // Update text to random phrase
                String color = BAND_COLORS[dominant];
                String phrase = getRandomPhrase(djInfo);
                plugin.getEntityPoolManager().updateTextContent(zoneName, "text_" + i,
                    color + "\u00A7l" + phrase);
            } else if (glowCountdowns[i] > 0) {
                glowCountdowns[i]--;
                shouldGlow = true;
            }

            int brightness = (int) lerp(6, 15, audio.getAmplitude());

            Transformation transform = new Transformation(
                new Vector3f(0, 0, 0),
                new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(baseScale, baseScale, baseScale),
                new AxisAngle4f(0, 0, 0, 1)
            );

            updates.add(EntityUpdate.builder("text_" + i)
                .transformation(transform)
                .brightness(brightness)
                .glow(shouldGlow)
                .interpolationDuration(2)
                .build());
        }

        if (!updates.isEmpty()) {
            plugin.getEntityPoolManager().batchUpdateEntities(zoneName, updates);
        }
    }

    private String getPhrase(int index) {
        List<String> phrases = config.getStringList("phrases", List.of(DEFAULT_PHRASES));
        if (phrases.isEmpty()) return "BEAT";
        return phrases.get(index % phrases.size());
    }

    private String getRandomPhrase(DJInfo djInfo) {
        List<String> phrases = config.getStringList("phrases", List.of(DEFAULT_PHRASES));

        // Occasionally show BPM if available
        if (djInfo.isPresent() && djInfo.bpm() > 0 && ThreadLocalRandom.current().nextInt(4) == 0) {
            return String.format("%.0f BPM", djInfo.bpm());
        }

        if (phrases.isEmpty()) return "BEAT";
        return phrases.get(ThreadLocalRandom.current().nextInt(phrases.size()));
    }
}
