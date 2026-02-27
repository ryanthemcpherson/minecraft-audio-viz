package com.audioviz.effects;

import com.audioviz.zones.VisualizationZone;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.Collection;

/**
 * Interface for beat-reactive effects like particles, screen shake, etc.
 *
 * <p>Ported from Paper: Location → Vec3d, Player → ServerPlayerEntity.
 */
public interface BeatEffect {

    String getId();

    String getName();

    /**
     * Trigger the effect at the specified location.
     *
     * @param location Where to trigger the effect (world coordinates)
     * @param zone     The visualization zone (for bounds and context)
     * @param intensity Effect intensity (0.0 - 1.0)
     * @param viewers  Players who should see the effect
     */
    void trigger(Vec3d location, VisualizationZone zone, double intensity,
                 Collection<ServerPlayerEntity> viewers);

    /**
     * Whether this effect should be triggered per-player or globally.
     */
    default boolean isPerPlayer() {
        return false;
    }
}
