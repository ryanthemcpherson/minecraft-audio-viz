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
 * Grid of BlockDisplay entities on the ground that ripple with color from bass.
 * Simulates LED dance floor tiles.
 */
public class FloorTileDecorator extends StageDecorator {

    private int beatFlashTicks = 0;

    public FloorTileDecorator(Stage stage, AudioVizPlugin plugin) {
        super("floor_tiles", "Stage Floor", stage, plugin);
    }

    @Override
    public DecoratorConfig getDefaultConfig() {
        DecoratorConfig config = new DecoratorConfig();
        config.set("grid_size", 6);
        config.set("ripple_speed", 2.0);
        config.set("target_role", "AUDIENCE");
        config.set("tile_material", "PURPLE_CONCRETE");
        return config;
    }

    @Override
    public void onActivate() {
        int gridSize = config.getInt("grid_size", 6);
        String targetRoleName = config.getString("target_role", "AUDIENCE");

        // Find the target zone for positioning
        StageZoneRole targetRole;
        try {
            targetRole = StageZoneRole.valueOf(targetRoleName.toUpperCase());
        } catch (IllegalArgumentException e) {
            targetRole = StageZoneRole.AUDIENCE;
        }

        String targetZoneName = stage.getZoneName(targetRole);
        Location origin;

        if (targetZoneName != null) {
            VisualizationZone targetZone = plugin.getZoneManager().getZone(targetZoneName);
            if (targetZone != null) {
                // Place floor at the base of the target zone
                origin = targetZone.getOrigin().clone().add(0, -0.5, 0);
            } else {
                origin = getStageRelativeLocation(-gridSize / 2.0, -0.5, -gridSize / 2.0);
            }
        } else {
            origin = getStageRelativeLocation(-gridSize / 2.0, -0.5, -gridSize / 2.0);
        }

        createDecoratorZone(origin, new Vector(gridSize, 1, gridSize));

        // Parse material
        String materialName = config.getString("tile_material", "PURPLE_CONCRETE");
        Material material = Material.matchMaterial(materialName);
        if (material == null) material = Material.PURPLE_CONCRETE;

        int entityCount = gridSize * gridSize;
        initBlockPool(entityCount, material);
    }

    @Override
    public void onDeactivate() {
        cleanupDecoratorZone();
    }

    @Override
    public void tick(AudioState audio, DJInfo djInfo) {
        int gridSize = config.getInt("grid_size", 6);
        float rippleSpeed = config.getFloat("ripple_speed", 2.0f);

        String zoneName = getDecoratorZoneName();
        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
        if (zone == null) return;

        double bass = audio.getBass();
        double center = gridSize / 2.0;

        // Beat flash tracking
        if (audio.isBeat()) {
            beatFlashTicks = 2;
        } else if (beatFlashTicks > 0) {
            beatFlashTicks--;
        }

        List<EntityUpdate> updates = new ArrayList<>();

        for (int row = 0; row < gridSize; row++) {
            for (int col = 0; col < gridSize; col++) {
                int entityIndex = row * gridSize + col;
                String entityId = "block_" + entityIndex;

                // Radial distance from center
                double dist = Math.sqrt((row - center) * (row - center) + (col - center) * (col - center));

                // Ripple wave
                double wave = Math.sin(dist - tickCount * rippleSpeed * 0.1) * 0.5 + 0.5;

                // Brightness from bass + wave
                int brightness;
                float scaleY;

                if (beatFlashTicks > 0) {
                    brightness = 15;
                    scaleY = 0.3f;
                } else {
                    brightness = (int) (bass * wave * 15);
                    brightness = Math.max(1, Math.min(15, brightness));
                    scaleY = (float) (0.1 + bass * wave * 0.2);
                }

                // Position each tile in the grid
                double normalizedX = (col + 0.5) / gridSize;
                double normalizedZ = (row + 0.5) / gridSize;
                Location tilePos = zone.localToWorld(normalizedX, 0, normalizedZ);

                Transformation transform = new Transformation(
                    new Vector3f(-0.45f, 0, -0.45f), // Center the tile
                    new AxisAngle4f(0, 0, 0, 1),
                    new Vector3f(0.9f, scaleY, 0.9f),
                    new AxisAngle4f(0, 0, 0, 1)
                );

                updates.add(EntityUpdate.builder(entityId)
                    .location(tilePos)
                    .transformation(transform)
                    .brightness(brightness)
                    .interpolationDuration(2)
                    .build());
            }
        }

        plugin.getEntityPoolManager().batchUpdateEntities(zoneName, updates);
    }
}
