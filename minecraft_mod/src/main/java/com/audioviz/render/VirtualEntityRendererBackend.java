package com.audioviz.render;

import com.audioviz.virtual.VirtualEntityPool;
import com.audioviz.zones.VisualizationZone;
import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import net.minecraft.server.world.ServerWorld;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 3D visualization renderer using Polymer virtual Display Entities.
 * Zero server-side entity overhead. Bundle-wrapped packet updates.
 * Used for Lua 3D patterns that need depth, rotation, and scale.
 */
public class VirtualEntityRendererBackend {
    private final Map<String, VirtualEntityPool> pools = new ConcurrentHashMap<>();

    public void initializePool(String zoneName, VisualizationZone zone,
                                int entityCount, ServerWorld world) {
        VirtualEntityPool pool = new VirtualEntityPool(entityCount);

        // Attach to world at zone origin — auto sends spawn/destroy as players load chunks
        ChunkAttachment.of(pool.getHolder(), world, zone.getOriginVec3d());

        pools.put(zoneName.toLowerCase(), pool);
    }

    /**
     * Apply batch entity updates from VJ server (Lua patterns).
     */
    public void applyBatchUpdate(String zoneName,
                                  java.util.List<VirtualEntityPool.EntityUpdate> updates) {
        VirtualEntityPool pool = pools.get(zoneName.toLowerCase());
        if (pool == null) return;
        pool.applyUpdates(updates);
    }

    /** Flush pending element changes. Call once per tick after all updates. */
    public void flush(String zoneName) {
        VirtualEntityPool pool = pools.get(zoneName.toLowerCase());
        if (pool == null) return;
        pool.getHolder().tick();
    }

    public void destroyPool(String zoneName) {
        VirtualEntityPool pool = pools.remove(zoneName.toLowerCase());
        if (pool != null) {
            pool.getHolder().destroy();
        }
    }

    public void resizePool(String zoneName, int newSize) {
        VirtualEntityPool pool = pools.get(zoneName.toLowerCase());
        if (pool != null) pool.resize(newSize);
    }

    public boolean hasPool(String zoneName) {
        return pools.containsKey(zoneName.toLowerCase());
    }

    /** Get all zone names with active virtual entity pools. */
    public Set<String> getActiveZones() {
        return Collections.unmodifiableSet(pools.keySet());
    }
}
