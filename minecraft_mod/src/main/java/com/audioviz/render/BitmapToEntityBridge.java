package com.audioviz.render;

import com.audioviz.virtual.VirtualEntityPool;
import com.audioviz.zones.VisualizationZone;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges the bitmap pattern engine to the virtual entity renderer.
 * Converts ARGB pixel data to colored block display entities.
 *
 * Each pixel becomes a BlockDisplayElement with the nearest concrete/terracotta color.
 * Only sends updates for pixels that changed since last frame (dirty tracking).
 *
 * Use for large displays (10x20+) where map tiles would be too bandwidth-heavy.
 * Resolution = entity count (1 block per pixel). Patterns render at wall dimensions.
 */
public class BitmapToEntityBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz");

    private final VirtualEntityRendererBackend virtualRenderer;
    private final Map<String, EntityWallState> walls = new ConcurrentHashMap<>();

    public BitmapToEntityBridge(VirtualEntityRendererBackend virtualRenderer) {
        this.virtualRenderer = virtualRenderer;
    }

    /**
     * Initialize an entity wall for a zone.
     * Entities are scaled and positioned to fill the zone's width and height dimensions,
     * centered at the zone's depth midpoint.
     *
     * @param zoneName    Zone identifier
     * @param zone        Visualization zone (provides origin, size, rotation)
     * @param pixelWidth  Wall width in pixels (columns of entities)
     * @param pixelHeight Wall height in pixels (rows of entities)
     * @param world       Server world
     * @param facing      Direction the wall faces (viewers look from the opposite side)
     */
    public void initializeWall(String zoneName, VisualizationZone zone,
                                int pixelWidth, int pixelHeight,
                                ServerWorld world, Direction facing) {
        String key = zoneName.toLowerCase();
        destroyWall(zoneName);

        int entityCount = pixelWidth * pixelHeight;
        virtualRenderer.initializePool(key, zone, entityCount, world);

        Vector3f zoneSize = zone.getSize();

        // Wall dimensions from zone size based on facing axis:
        // NORTH/SOUTH facing: wall spans X (width) and Y (height), thin on Z
        // EAST/WEST facing: wall spans Z (width) and Y (height), thin on X
        boolean zAxis = facing.getAxis() == Direction.Axis.Z;
        float wallWidth = zAxis ? zoneSize.x : zoneSize.z;
        float wallHeight = zoneSize.y;

        // Cell dimensions (each pixel fills a proportional area of the zone)
        float cellW = wallWidth / pixelWidth;
        float cellH = wallHeight / pixelHeight;

        // Entity scale: fill cell area, thin on depth axis
        Vector3f scale = zAxis
            ? new Vector3f(cellW, cellH, 0.05f)
            : new Vector3f(0.05f, cellH, cellW);

        // Depth offset: center wall in the zone along the depth axis
        float depthOffset = zAxis ? zoneSize.z * 0.5f : zoneSize.x * 0.5f;

        List<VirtualEntityPool.EntityUpdate> posUpdates = new ArrayList<>(entityCount);
        for (int py = 0; py < pixelHeight; py++) {
            for (int px = 0; px < pixelWidth; px++) {
                int idx = py * pixelWidth + px;
                float u = px * cellW;
                float v = (pixelHeight - 1 - py) * cellH;

                double relX, relY, relZ;
                relY = v;

                switch (facing) {
                    case NORTH -> { relX = u; relZ = depthOffset; }
                    case SOUTH -> { relX = wallWidth - u - cellW; relZ = depthOffset; }
                    case EAST  -> { relX = depthOffset; relZ = u; }
                    case WEST  -> { relX = depthOffset; relZ = wallWidth - u - cellW; }
                    default    -> { relX = u; relZ = 0; }
                }

                // ChunkAttachment is at block center (+0.5, +0.5) but zone starts
                // at block corner, so offset entities to align with zone bounds.
                relX -= 0.5;
                relZ -= 0.5;

                posUpdates.add(new VirtualEntityPool.EntityUpdate(
                    idx, new Vec3d(relX, relY, relZ), scale, null, true));
            }
        }
        virtualRenderer.applyBatchUpdate(key, posUpdates);

        walls.put(key, new EntityWallState(pixelWidth, pixelHeight));

        LOGGER.info("Entity wall '{}' initialized: {}x{} = {} entities, facing {}, zone-scaled ({}x{})",
            zoneName, pixelWidth, pixelHeight, entityCount, facing,
            String.format("%.1f", wallWidth), String.format("%.1f", wallHeight));
    }

    /**
     * Apply an ARGB frame to the entity wall.
     * Only updates entities whose color changed since last frame.
     */
    public void applyFrame(String zoneName, int[] argbPixels, int width, int height) {
        EntityWallState wall = walls.get(zoneName.toLowerCase());
        if (wall == null) return;

        int count = Math.min(argbPixels.length, wall.width * wall.height);
        List<VirtualEntityPool.EntityUpdate> updates = null;

        for (int i = 0; i < count; i++) {
            BlockState newState = BlockColorMapper.mapColor(argbPixels[i]);
            if (!newState.equals(wall.lastStates[i])) {
                if (updates == null) updates = new ArrayList<>();
                updates.add(new VirtualEntityPool.EntityUpdate(i, null, null, newState, null));
                wall.lastStates[i] = newState;
            }
        }

        if (updates != null) {
            virtualRenderer.applyBatchUpdate(zoneName.toLowerCase(), updates);
        }
    }

    public void destroyWall(String zoneName) {
        String key = zoneName.toLowerCase();
        if (walls.remove(key) != null) {
            virtualRenderer.destroyPool(key);
        }
    }

    public boolean hasWall(String zoneName) {
        return walls.containsKey(zoneName.toLowerCase());
    }

    public Set<String> getActiveWalls() {
        return Collections.unmodifiableSet(walls.keySet());
    }

    private static class EntityWallState {
        final int width;
        final int height;
        final BlockState[] lastStates;

        EntityWallState(int width, int height) {
            this.width = width;
            this.height = height;
            this.lastStates = new BlockState[width * height];
        }
    }
}
