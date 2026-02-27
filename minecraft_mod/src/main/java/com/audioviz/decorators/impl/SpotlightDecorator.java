package com.audioviz.decorators.impl;

import com.audioviz.AudioVizMod;
import com.audioviz.decorators.*;
import com.audioviz.patterns.AudioState;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageZoneRole;
import com.audioviz.zones.VisualizationZone;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.Vec3d;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Narrow columns of BlockDisplay entities that sweep and change color with audio.
 * Simulates concert spotlight/laser beams.
 *
 * <p>Ported from Paper: Material -> BlockState, Location -> Vec3d,
 * EntityUpdate -> DecoratorUpdate, Transformation -> scale/rotation/translation.
 */
public class SpotlightDecorator extends StageDecorator {

    private static final BlockState[] BAND_MATERIALS = {
        Blocks.RED_STAINED_GLASS.getDefaultState(),
        Blocks.ORANGE_STAINED_GLASS.getDefaultState(),
        Blocks.YELLOW_STAINED_GLASS.getDefaultState(),
        Blocks.LIME_STAINED_GLASS.getDefaultState(),
        Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState()
    };

    private int previousDominantBand = -1;

    public SpotlightDecorator(Stage stage, AudioVizMod mod) {
        super("spotlight", "Spotlights", stage, mod);
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

        String mainZoneName = stage.getZoneName(StageZoneRole.MAIN_STAGE);
        Vec3d origin;
        Vector3f size;

        if (mainZoneName != null) {
            VisualizationZone mainZone = mod.getZoneManager().getZone(mainZoneName);
            if (mainZone != null) {
                origin = mainZone.getOriginVec3d();
                size = mainZone.getSize();
            } else {
                origin = getStageRelativePosition(-8, 0, -5);
                size = new Vector3f(16, 12, 10);
            }
        } else {
            origin = getStageRelativePosition(-8, 0, -5);
            size = new Vector3f(16, 12, 10);
        }

        createDecoratorZone(origin, size);
        initBlockPool(beamCount, Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState());
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
        VisualizationZone zone = mod.getZoneManager().getZone(zoneName);
        if (zone == null) return;

        // Update material when dominant band changes
        int dominant = getDominantBand(audio);
        if (dominant != previousDominantBand) {
            previousDominantBand = dominant;
            BlockState beamBlock = BAND_MATERIALS[Math.min(dominant, BAND_MATERIALS.length - 1)];
            for (int i = 0; i < beamCount; i++) {
                mod.getDecoratorEntityManager().updateBlockState(zoneName, "block_" + i, beamBlock);
            }
        }

        double elapsed = tickCount * 0.1 * sweepSpeed;
        List<DecoratorUpdate> updates = new ArrayList<>();

        for (int i = 0; i < beamCount; i++) {
            double phase = i * (2 * Math.PI / beamCount);
            float sweepAngle = (float) (sweepRange * Math.sin(elapsed + phase));

            float currentHeight = beamHeight * (1.0f + 0.3f * (float) audio.getAmplitude());
            if (audio.isBeat()) {
                currentHeight *= 1.5f;
            }

            int brightness = audio.isBeat() ? 15 : (int) lerp(5, 15, audio.getAmplitude());

            double normalizedX = (i % 2 == 0) ? 0.1 : 0.9;
            double normalizedZ = (i < beamCount / 2) ? 0.2 : 0.8;
            Vec3d beamPos = zone.localToWorld(normalizedX, 0, normalizedZ);

            float radians = (float) Math.toRadians(sweepAngle);
            Quaternionf leftRot = new Quaternionf(new AxisAngle4f(radians, 0, 0, 1));

            updates.add(DecoratorUpdate.builder("block_" + i)
                .position(beamPos)
                .translation(new Vector3f(-beamWidth / 2, 0, -beamWidth / 2))
                .leftRotation(leftRot)
                .scale(new Vector3f(beamWidth, currentHeight, beamWidth))
                .brightness(brightness)
                .glow(audio.isBeat())
                .interpolationDuration(2)
                .build());
        }

        mod.getDecoratorEntityManager().batchUpdate(zoneName, updates);
    }
}
