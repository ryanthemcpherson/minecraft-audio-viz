package com.audioviz.effects;

import com.audioviz.zones.VisualizationZone;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * Interface for beat-reactive effects like particles, screen shake, etc.
 */
public interface BeatEffect {

    /**
     * Unique identifier for this effect.
     */
    String getId();

    /**
     * Display name for UI.
     */
    String getName();

    /**
     * Trigger the effect at the specified location.
     *
     * @param location Where to trigger the effect
     * @param zone The visualization zone (for bounds and context)
     * @param intensity Effect intensity (0.0 - 1.0)
     * @param viewers Players who should see the effect
     */
    void trigger(Location location, VisualizationZone zone, double intensity, Collection<Player> viewers);

    /**
     * Whether this effect should be triggered per-player or globally.
     */
    default boolean isPerPlayer() {
        return false;
    }
}
