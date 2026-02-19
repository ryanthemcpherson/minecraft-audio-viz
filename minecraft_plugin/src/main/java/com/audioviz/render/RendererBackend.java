package com.audioviz.render;

import com.audioviz.entities.EntityUpdate;
import com.audioviz.patterns.AudioState;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.Material;

import java.util.List;
import java.util.Set;

/**
 * Contract for rendering backends. Each backend handles the visual
 * representation of audio data within a Minecraft zone.
 *
 * <p>Lifecycle: {@link #initialize} → {@link #updateFrame} (repeated) → {@link #teardown}
 *
 * <p>Implementations must be safe to call from the Bukkit main thread.
 * Async operations should be scheduled internally.
 */
public interface RendererBackend {

    /**
     * @return the backend type identifier
     */
    RendererBackendType getType();

    /**
     * Initialize rendering resources for a zone.
     *
     * @param zone     the visualization zone
     * @param count    number of visual elements to allocate
     * @param material the block material (for display entity backends)
     */
    void initialize(VisualizationZone zone, int count, Material material);

    /**
     * Apply a batch of entity updates in a single tick.
     * This is the hot path — called every frame (~50Hz).
     *
     * @param zoneName the zone name
     * @param updates  list of entity position/transform/property updates
     */
    void updateFrame(String zoneName, List<EntityUpdate> updates);

    /**
     * Update audio state for audio-reactive rendering (particles, holograms).
     *
     * @param audioState current audio analysis data
     */
    void updateAudioState(AudioState audioState);

    /**
     * Show or hide all visual elements in a zone.
     * Used by blackout effect.
     *
     * @param zoneName the zone name
     * @param visible  true to show, false to hide
     */
    void setVisible(String zoneName, boolean visible);

    /**
     * Clean up all rendering resources for a zone.
     *
     * @param zoneName the zone name
     */
    void teardown(String zoneName);

    /**
     * Get the number of active visual elements in a zone.
     *
     * @param zoneName the zone name
     * @return element count
     */
    int getElementCount(String zoneName);

    /**
     * Get all element IDs in a zone.
     *
     * @param zoneName the zone name
     * @return set of element identifiers
     */
    Set<String> getElementIds(String zoneName);
}
