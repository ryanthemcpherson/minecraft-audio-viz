package com.audioviz.decorators.impl;

import com.audioviz.AudioVizMod;
import com.audioviz.decorators.*;
import com.audioviz.patterns.AudioState;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageZoneRole;
import com.audioviz.zones.VisualizationZone;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Grid of BlockDisplay entities on the ground that ripple with color from bass.
 * Simulates LED dance floor tiles.
 *
 * <p>Ported from Paper: Material -> BlockState, Location -> Vec3d,
 * EntityUpdate -> DecoratorUpdate, zone.localToWorld preserved.
 */
public class FloorTileDecorator extends StageDecorator {

    private int beatFlashTicks = 0;

    public FloorTileDecorator(Stage stage, AudioVizMod mod) {
        super("floor_tiles", "Stage Floor", stage, mod);
    }

    @Override
    public DecoratorConfig getDefaultConfig() {
        DecoratorConfig config = new DecoratorConfig();
        config.set("grid_size", 6);
        config.set("ripple_speed", 2.0);
        config.set("target_role", "AUDIENCE");
        config.set("tile_material", "purple_concrete");
        return config;
    }

    @Override
    public void onActivate() {
        int gridSize = config.getInt("grid_size", 6);
        String targetRoleName = config.getString("target_role", "AUDIENCE");

        StageZoneRole targetRole;
        try {
            targetRole = StageZoneRole.valueOf(targetRoleName.toUpperCase());
        } catch (IllegalArgumentException e) {
            targetRole = StageZoneRole.AUDIENCE;
        }

        String targetZoneName = stage.getZoneName(targetRole);
        Vec3d origin;

        if (targetZoneName != null) {
            VisualizationZone targetZone = mod.getZoneManager().getZone(targetZoneName);
            if (targetZone != null) {
                Vec3d zoneOrigin = targetZone.getOriginVec3d();
                origin = new Vec3d(zoneOrigin.x, zoneOrigin.y - 0.5, zoneOrigin.z);
            } else {
                origin = getStageRelativePosition(-gridSize / 2.0, -0.5, -gridSize / 2.0);
            }
        } else {
            origin = getStageRelativePosition(-gridSize / 2.0, -0.5, -gridSize / 2.0);
        }

        createDecoratorZone(origin, new Vector3f(gridSize, 1, gridSize));

        // Parse block state from config
        String materialName = config.getString("tile_material", "purple_concrete");
        BlockState blockState = resolveBlockState(materialName);

        int entityCount = gridSize * gridSize;
        initBlockPool(entityCount, blockState);
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
        VisualizationZone zone = mod.getZoneManager().getZone(zoneName);
        if (zone == null) return;

        double bass = audio.getBass();
        double center = gridSize / 2.0;

        if (audio.isBeat()) {
            beatFlashTicks = 2;
        } else if (beatFlashTicks > 0) {
            beatFlashTicks--;
        }

        List<DecoratorUpdate> updates = new ArrayList<>();

        for (int row = 0; row < gridSize; row++) {
            for (int col = 0; col < gridSize; col++) {
                int entityIndex = row * gridSize + col;
                String entityId = "block_" + entityIndex;

                double dist = Math.sqrt((row - center) * (row - center) + (col - center) * (col - center));
                double wave = Math.sin(dist - tickCount * rippleSpeed * 0.1) * 0.5 + 0.5;

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

                double normalizedX = (col + 0.5) / gridSize;
                double normalizedZ = (row + 0.5) / gridSize;
                Vec3d tilePos = zone.localToWorld(normalizedX, 0, normalizedZ);

                updates.add(DecoratorUpdate.builder(entityId)
                    .position(tilePos)
                    .translation(new Vector3f(-0.45f, 0, -0.45f))
                    .scale(new Vector3f(0.9f, scaleY, 0.9f))
                    .brightness(brightness)
                    .interpolationDuration(2)
                    .build());
            }
        }

        mod.getDecoratorEntityManager().batchUpdate(zoneName, updates);
    }

    private static BlockState resolveBlockState(String name) {
        return com.audioviz.render.MaterialResolver.resolve(name);
    }
}
