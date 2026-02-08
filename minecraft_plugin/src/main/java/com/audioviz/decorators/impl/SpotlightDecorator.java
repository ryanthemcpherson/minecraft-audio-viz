package com.audioviz.decorators.impl;

import com.audioviz.AudioVizPlugin;
import com.audioviz.decorators.DJInfo;
import com.audioviz.decorators.DecoratorConfig;
import com.audioviz.decorators.StageDecorator;
import com.audioviz.entities.EntityUpdate;
import com.audioviz.patterns.AudioState;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageZoneRole;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Narrow columns of BlockDisplay entities that sweep and change color with audio.
 * Simulates concert spotlight/laser beams.
 */
public class SpotlightDecorator extends StageDecorator {

    // Materials for each frequency band
    private static final Material[] BAND_MATERIALS = {
        Material.RED_STAINED_GLASS,
        Material.ORANGE_STAINED_GLASS,
        Material.YELLOW_STAINED_GLASS,
        Material.LIME_STAINED_GLASS,
        Material.LIGHT_BLUE_STAINED_GLASS
    };

    private int previousDominantBand = -1;

    public SpotlightDecorator(Stage stage, AudioVizPlugin plugin) {
        super("spotlight", "Spotlights", stage, plugin);
    }

    @Override
    public DecoratorConfig getDefaultConfig() {
        DecoratorConfig config = new DecoratorConfig();
        config.set("beam_count", 4);
        config.set("beam_height", 8.0);
        config.set("sweep_speed", 1.0);
        config.set("sweep_range", 45.0);
        config.set("beam_width", 0.2);
        return config;
    }

    @Override
    public void onActivate() {
        int beamCount = config.getInt("beam_count", 4);

        // Find main stage zone for positioning beams at its edges
        String mainZoneName = stage.getZoneName(StageZoneRole.MAIN_STAGE);
        Location origin;
        Vector size;

        if (mainZoneName != null) {
            VisualizationZone mainZone = plugin.getZoneManager().getZone(mainZoneName);
            if (mainZone != null) {
                origin = mainZone.getOrigin().clone();
                size = mainZone.getSize().clone();
            } else {
                origin = getStageRelativeLocation(-8, 0, -5);
                size = new Vector(16, 12, 10);
            }
        } else {
            origin = getStageRelativeLocation(-8, 0, -5);
            size = new Vector(16, 12, 10);
        }

        createDecoratorZone(origin, size);
        initBlockPool(beamCount, Material.LIGHT_BLUE_STAINED_GLASS);
    }

    @Override
    public void onDeactivate() {
        cleanupDecoratorZone();
    }

    @Override
    public void tick(AudioState audio, DJInfo djInfo) {
        int beamCount = config.getInt("beam_count", 4);
        float beamHeight = config.getFloat("beam_height", 8.0f);
        float sweepSpeed = config.getFloat("sweep_speed", 1.0f);
        float sweepRange = config.getFloat("sweep_range", 45.0f);
        float beamWidth = config.getFloat("beam_width", 0.2f);

        String zoneName = getDecoratorZoneName();
        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
        if (zone == null) return;

        // Update material when dominant band changes
        int dominant = getDominantBand(audio);
        if (dominant != previousDominantBand) {
            previousDominantBand = dominant;
            Material beamMaterial = BAND_MATERIALS[Math.min(dominant, BAND_MATERIALS.length - 1)];
            for (int i = 0; i < beamCount; i++) {
                plugin.getEntityPoolManager().updateBlockMaterial(zoneName, "block_" + i, beamMaterial);
            }
        }

        // Calculate sweep and build batch updates
        double elapsed = tickCount * 0.1 * sweepSpeed;
        List<EntityUpdate> updates = new ArrayList<>();

        for (int i = 0; i < beamCount; i++) {
            // Phase offset per beam
            double phase = i * (2 * Math.PI / beamCount);
            float sweepAngle = (float) (sweepRange * Math.sin(elapsed + phase));

            // Beam height extends on beat
            float currentHeight = beamHeight * (1.0f + 0.3f * (float) audio.getAmplitude());
            if (audio.isBeat()) {
                currentHeight *= 1.5f;
            }

            // Brightness
            int brightness = audio.isBeat() ? 15 : (int) lerp(5, 15, audio.getAmplitude());

            // Position beams around the zone edges
            double normalizedX = (i % 2 == 0) ? 0.1 : 0.9;
            double normalizedZ = (i < beamCount / 2) ? 0.2 : 0.8;
            Location beamPos = zone.localToWorld(normalizedX, 0, normalizedZ);

            // Transformation: narrow column, rotated by sweep angle
            float radians = (float) Math.toRadians(sweepAngle);
            Transformation transform = new Transformation(
                new Vector3f(-beamWidth / 2, 0, -beamWidth / 2),
                new AxisAngle4f(radians, 0, 0, 1), // Sweep rotation on Z-axis
                new Vector3f(beamWidth, currentHeight, beamWidth),
                new AxisAngle4f(0, 0, 0, 1)
            );

            updates.add(EntityUpdate.builder("block_" + i)
                .location(beamPos)
                .transformation(transform)
                .brightness(brightness)
                .glow(audio.isBeat())
                .interpolationDuration(2)
                .build());
        }

        plugin.getEntityPoolManager().batchUpdateEntities(zoneName, updates);
    }
}
