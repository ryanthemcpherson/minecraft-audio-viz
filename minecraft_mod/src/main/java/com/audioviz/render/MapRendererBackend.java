package com.audioviz.render;

import com.audioviz.map.MapDisplayManager;
import com.audioviz.map.MapItemFrameSpawner;
import com.audioviz.zones.VisualizationZone;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Direction;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Map-based bitmap renderer backend.
 * Receives ARGB frame data and routes it through MapDisplayManager → MapPacketSender.
 * Uses invisible glow item frames to hold maps — no physical blocks needed.
 */
public class MapRendererBackend {
    private final Map<String, MapDisplayManager> displays = new ConcurrentHashMap<>();
    private final Map<String, MapItemFrameSpawner.SpawnedGrid> grids = new ConcurrentHashMap<>();

    /**
     * Initialize a map display for a zone.
     */
    public void initializeDisplay(String zoneName, VisualizationZone zone,
                                   int pixelWidth, int pixelHeight,
                                   ServerWorld world, Direction facing) {
        String key = zoneName.toLowerCase();

        // Destroy existing display if any
        destroyDisplay(zoneName);

        MapDisplayManager display = new MapDisplayManager(pixelWidth, pixelHeight);

        var grid = MapItemFrameSpawner.spawnGrid(
            world, zone.getOrigin(), facing,
            display.getTileCountX(), display.getTileCountZ()
        );

        for (var tile : grid.tiles()) {
            display.setMapId(tile.tileX(), tile.tileZ(), tile.mapId());
        }

        displays.put(key, display);
        grids.put(key, grid);
    }

    /**
     * Apply an ARGB frame (from bitmap pattern system).
     */
    public void applyFrame(String zoneName, int[] argbPixels, int width, int height) {
        MapDisplayManager display = displays.get(zoneName.toLowerCase());
        if (display == null) return;
        display.writeFrame(argbPixels, width, height);
    }

    /** Send all dirty tile updates to nearby players. Call once per tick. */
    public void flush(String zoneName, Collection<ServerPlayerEntity> players) {
        MapDisplayManager display = displays.get(zoneName.toLowerCase());
        if (display == null) return;
        display.sendUpdates(players);
    }

    /** No-op — glow item frames are real entities, no manual ticking needed. */
    public void tickHolders() {
        // Item frames are server entities — no manual tick required.
    }

    /** Cleanup: remove virtual displays when zone is deleted. */
    public void destroyDisplay(String zoneName) {
        String key = zoneName.toLowerCase();
        var grid = grids.remove(key);
        MapItemFrameSpawner.despawnGrid(grid);
        displays.remove(key);
    }

    /** Get all zone names with active map displays. */
    public Set<String> getActiveZones() {
        return Collections.unmodifiableSet(displays.keySet());
    }

    /** Check if a zone has an active map display. */
    public boolean hasDisplay(String zoneName) {
        return displays.containsKey(zoneName.toLowerCase());
    }
}
