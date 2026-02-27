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
 * Replaces TextDisplay entity rendering with map items on invisible item frames.
 */
public class MapRendererBackend {
    private final Map<String, MapDisplayManager> displays = new ConcurrentHashMap<>();
    private final Map<String, List<MapItemFrameSpawner.SpawnedTile>> spawnedTiles = new ConcurrentHashMap<>();

    /**
     * Initialize a map display for a zone.
     */
    public void initializeDisplay(String zoneName, VisualizationZone zone,
                                   int pixelWidth, int pixelHeight,
                                   ServerWorld world, Direction facing) {
        MapDisplayManager display = new MapDisplayManager(pixelWidth, pixelHeight);

        var tiles = MapItemFrameSpawner.spawnGrid(
            world, zone.getOrigin(), facing,
            display.getTileCountX(), display.getTileCountZ()
        );

        for (var tile : tiles) {
            display.setMapId(tile.tileX(), tile.tileZ(), tile.mapId());
        }

        displays.put(zoneName.toLowerCase(), display);
        spawnedTiles.put(zoneName.toLowerCase(), tiles);
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

    /** Cleanup: remove item frames when zone is deleted. */
    public void destroyDisplay(String zoneName) {
        var tiles = spawnedTiles.remove(zoneName.toLowerCase());
        MapItemFrameSpawner.despawnGrid(tiles);
        displays.remove(zoneName.toLowerCase());
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
