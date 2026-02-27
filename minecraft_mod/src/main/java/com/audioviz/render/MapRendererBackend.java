package com.audioviz.render;

import com.audioviz.map.MapDisplayManager;
import com.audioviz.map.MapItemFrameSpawner;
import com.audioviz.zones.VisualizationZone;
import net.minecraft.entity.decoration.GlowItemFrameEntity;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Map-based bitmap renderer backend.
 * Receives ARGB frame data and routes it through MapDisplayManager → MapPacketSender.
 * Uses invisible glow item frames to hold maps — no physical blocks needed.
 *
 * The tile grid is automatically sized to fill the zone's wall dimensions
 * and centered on the zone via localToWorld (respects rotation).
 */
public class MapRendererBackend {
    /**
     * Render scale factor: patterns render at 1/RENDER_SCALE resolution per axis,
     * then get nearest-neighbor upscaled to fill the display. This makes bitmap
     * effects bold enough to see from a distance (each "pixel" covers RENDER_SCALE×RENDER_SCALE
     * map pixels, i.e. ~3cm blocks instead of ~0.8cm sub-pixels).
     */
    public static final int RENDER_SCALE = 4;

    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz.map");

    private final Map<String, MapDisplayManager> displays = new ConcurrentHashMap<>();
    private final Map<String, MapItemFrameSpawner.SpawnedGrid> grids = new ConcurrentHashMap<>();
    /** Tracks which zones have had orphan cleanup run (once per zone per session). */
    private final Set<String> orphanCleanedZones = new HashSet<>();

    /**
     * Initialize a map display for a zone.
     * Tile count is derived from zone wall dimensions (1 tile = 1 block = 128x128 px),
     * capped at 64 total tiles for performance. The grid is centered via zone.localToWorld().
     *
     * @return the pattern resolution [width, height] — display pixels / RENDER_SCALE.
     *         Callers should render patterns at this size; writeFrame handles upscaling.
     */
    public int[] initializeDisplay(String zoneName, VisualizationZone zone,
                                    int pixelWidth, int pixelHeight,
                                    ServerWorld world, Direction facing) {
        String key = zoneName.toLowerCase();
        destroyDisplay(zoneName);

        // Derive tile count from zone local dimensions (X = width, Y = height)
        Vector3f zoneSize = zone.getSize();
        int tilesX = Math.max(1, (int) zoneSize.x);
        int tilesZ = Math.max(1, (int) zoneSize.y);

        // Cap total tiles for performance: each tile = 1 map entity + update packets.
        // 64 tiles = max 1M pixels at 128px/tile, matching BitmapFrameBuffer limit.
        int MAX_TILES = 64;
        int maxTilesX = tilesX;
        int maxTilesZ = tilesZ;
        if (tilesX * tilesZ > MAX_TILES) {
            float scale = (float) Math.sqrt((double) MAX_TILES / ((long) tilesX * tilesZ));
            tilesX = Math.max(1, (int) (tilesX * scale));
            tilesZ = Math.max(1, (int) (tilesZ * scale));
            // Fill remaining budget proportionally, but never exceed zone dimensions
            while ((tilesX + 1) * tilesZ <= MAX_TILES && tilesX + 1 <= maxTilesX) tilesX++;
            while (tilesX * (tilesZ + 1) <= MAX_TILES && tilesZ + 1 <= maxTilesZ) tilesZ++;
        }

        int actualPixelW = tilesX * 128;
        int actualPixelH = tilesZ * 128;

        MapDisplayManager display = new MapDisplayManager(actualPixelW, actualPixelH);

        var grid = MapItemFrameSpawner.spawnGridInZone(
            world, zone, facing,
            display.getTileCountX(), display.getTileCountZ()
        );

        for (var tile : grid.tiles()) {
            display.setMapId(tile.tileX(), tile.tileZ(), tile.mapId());
        }

        displays.put(key, display);
        grids.put(key, grid);

        // Return reduced resolution — patterns render at 1/RENDER_SCALE,
        // MapDisplayManager.writeFrame handles nearest-neighbor upscaling.
        return new int[]{ Math.max(1, actualPixelW / RENDER_SCALE),
                          Math.max(1, actualPixelH / RENDER_SCALE) };
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
        String key = zoneName.toLowerCase();
        MapDisplayManager display = displays.get(key);
        if (display == null) return;

        // One-time orphan cleanup: when a player first enters the zone area,
        // chunks are loaded, so we can find and remove old item frames from
        // previous server sessions that overlap with our current grid.
        if (!players.isEmpty() && !orphanCleanedZones.contains(key)) {
            orphanCleanedZones.add(key);
            cleanupOrphanedFrames(key, players.iterator().next());
        }

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

    /**
     * Remove orphaned glow item frames (from previous server sessions) near a zone's grid.
     * Finds all invisible glow item frames holding filled maps within a generous bounding box,
     * then discards any that aren't tracked in our current grid.
     */
    private void cleanupOrphanedFrames(String key, ServerPlayerEntity anyPlayer) {
        var grid = grids.get(key);
        if (grid == null || grid.tiles().isEmpty()) return;

        // Collect our tracked frame entity IDs
        Set<Integer> ownedFrameIds = new HashSet<>();
        for (var tile : grid.tiles()) {
            if (tile.frame() != null) ownedFrameIds.add(tile.frame().getId());
        }

        // Build a bounding box around our grid tiles
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (var tile : grid.tiles()) {
            if (tile.frame() != null) {
                var pos = tile.frame().getBlockPos();
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());
            }
        }

        // Expand by 2 blocks to catch overlapping frames
        Box searchBox = new Box(minX - 2, minY - 2, minZ - 2, maxX + 2, maxY + 2, maxZ + 2);
        ServerWorld world = (ServerWorld) anyPlayer.getEntityWorld();

        List<GlowItemFrameEntity> orphans = world.getEntitiesByClass(
            GlowItemFrameEntity.class, searchBox,
            frame -> frame.isInvisible()
                && frame.getHeldItemStack().isOf(Items.FILLED_MAP)
                && !ownedFrameIds.contains(frame.getId())
        );

        if (!orphans.isEmpty()) {
            LOGGER.info("Cleaning up {} orphaned map frames near zone '{}'", orphans.size(), key);
            for (GlowItemFrameEntity frame : orphans) {
                frame.discard();
            }
        }
    }
}
