package com.audioviz.render;

import com.audioviz.entities.EntityPoolManager;
import com.audioviz.entities.EntityUpdate;
import com.audioviz.patterns.AudioState;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.Material;

import java.util.List;
import java.util.Set;

/**
 * Renderer backend backed by Minecraft Display Entities (BlockDisplay, TextDisplay, ItemDisplay).
 *
 * <p>This is the default and highest-fidelity rendering backend. Display Entities support:
 * <ul>
 *   <li>Per-entity transformation (position, scale, rotation) with interpolation</li>
 *   <li>Per-entity brightness and glow</li>
 *   <li>Block material changes</li>
 *   <li>Batch updates in a single scheduler tick</li>
 * </ul>
 *
 * <p>Wraps {@link EntityPoolManager} to implement the {@link RendererBackend} contract.
 */
public class DisplayEntitiesBackend implements RendererBackend {

    private final EntityPoolManager poolManager;

    public DisplayEntitiesBackend(EntityPoolManager poolManager) {
        this.poolManager = poolManager;
    }

    @Override
    public RendererBackendType getType() {
        return RendererBackendType.DISPLAY_ENTITIES;
    }

    @Override
    public void initialize(VisualizationZone zone, int count, Material material) {
        poolManager.initializeBlockPool(zone.getName(), count, material);
    }

    @Override
    public void updateFrame(String zoneName, List<EntityUpdate> updates) {
        poolManager.batchUpdateEntities(zoneName, updates);
    }

    @Override
    public void updateAudioState(AudioState audioState) {
        // Display entities don't react to audio state directly;
        // entity updates come through updateFrame().
    }

    @Override
    public void setVisible(String zoneName, boolean visible) {
        for (String entityId : poolManager.getEntityIds(zoneName)) {
            poolManager.setEntityVisible(zoneName, entityId, visible);
        }
    }

    @Override
    public void teardown(String zoneName) {
        poolManager.cleanupZone(zoneName);
    }

    @Override
    public int getElementCount(String zoneName) {
        return poolManager.getEntityCount(zoneName);
    }

    @Override
    public Set<String> getElementIds(String zoneName) {
        return poolManager.getEntityIds(zoneName);
    }

    /**
     * Access the underlying pool manager for display-entity-specific operations
     * (e.g., glow, brightness, interpolation) that are not part of the generic contract.
     */
    public EntityPoolManager getPoolManager() {
        return poolManager;
    }
}
