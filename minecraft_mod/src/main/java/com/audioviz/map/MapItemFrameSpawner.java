package com.audioviz.map;

import com.audioviz.zones.VisualizationZone;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.entity.decoration.GlowItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;
import org.slf4j.LoggerFactory;

import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;

/**
 * Spawns a grid of invisible glow item frames holding filled maps.
 * Item frames are Fixed (won't break) and Invisible (only the map content shows).
 * No barrier blocks needed — Fixed item frames don't require a supporting block.
 */
public class MapItemFrameSpawner {

    /**
     * Spawn a grid of map item frames centered on a zone's wall face.
     * Tile positions are computed with exact double arithmetic for cardinal
     * rotations (0/90/180/270) to avoid float-precision gaps between tiles.
     *
     * In the zone's LOCAL coordinate space:
     *   - Width spans local X (tile columns)
     *   - Height spans local Y (tile rows, upward)
     *   - Depth is at local Z = size.z/2 (center of zone depth)
     *
     * @param world    Server world to spawn in
     * @param zone     Visualization zone (provides origin, size, rotation)
     * @param facing   Direction the display faces (derived from zone rotation)
     * @param tilesX   Number of tiles horizontally
     * @param tilesZ   Number of tiles vertically (upward)
     * @return         SpawnedGrid with tile info
     */
    public static SpawnedGrid spawnGridInZone(ServerWorld world, VisualizationZone zone,
                                               Direction facing, int tilesX, int tilesZ) {
        // Clean up any orphaned glow item frames from previous server sessions
        // within the zone's bounding box, so we don't stack frames on restarts.
        cleanupOrphanedFrames(world, zone);

        List<SpawnedTile> tiles = new ArrayList<>();
        Vector3f zoneSize = zone.getSize();
        BlockPos origin = zone.getOrigin();

        // All arithmetic in double to avoid float→double promotion precision loss.
        double wallWidth = zoneSize.x;
        double wallHeight = zoneSize.y;
        double depthZ = zoneSize.z / 2.0;
        // Round offsets to nearest integer so tiles land on exact block positions.
        // Without rounding, an odd gap (e.g. wallWidth=21, tiles=16) gives offsetW=2.5,
        // and BlockPos.ofFloored biases the grid half a block toward lower coordinates.
        int offsetW = (int) Math.round((wallWidth - tilesX) / 2.0);
        int offsetH = (int) Math.round((wallHeight - tilesZ) / 2.0);

        // Cardinal rotation switch avoids trig rounding (cos(π/2) ≠ 0 in IEEE 754)
        int rot = ((int) zone.getRotation() % 360 + 360) % 360;

        BlockPos firstPos = null;

        for (int tz = 0; tz < tilesZ; tz++) {
            for (int tx = 0; tx < tilesX; tx++) {
                // Local coordinates in block units (integer, exact).
                // Mirror horizontal: tx=0 (left of image) → highest local X (viewer's left).
                int localX = offsetW + (tilesX - 1 - tx);
                int localY = offsetH + tz;

                double worldX, worldY, worldZ;
                worldY = origin.getY() + localY;

                switch (rot) {
                    case 0 -> {
                        worldX = origin.getX() + localX;
                        worldZ = origin.getZ() + depthZ;
                    }
                    case 90 -> {
                        worldX = origin.getX() - depthZ;
                        worldZ = origin.getZ() + localX;
                    }
                    case 180 -> {
                        worldX = origin.getX() - localX;
                        worldZ = origin.getZ() - depthZ;
                    }
                    case 270 -> {
                        worldX = origin.getX() + depthZ;
                        worldZ = origin.getZ() - localX;
                    }
                    default -> {
                        // Non-cardinal: fall back to localToWorld (uses trig)
                        Vec3d worldPos = zone.localToWorld(
                            localX / wallWidth, localY / wallHeight, 0.5);
                        worldX = worldPos.x;
                        worldY = worldPos.y;
                        worldZ = worldPos.z;
                    }
                }

                BlockPos tilePos = BlockPos.ofFloored(worldX, worldY, worldZ);
                if (firstPos == null) firstPos = tilePos;

                tiles.add(spawnTile(world, tilePos, facing, tx, tz));
            }
        }

        LoggerFactory.getLogger("audioviz").info(
            "Spawned map grid {}x{} at {} facing {} (zone: {}, size: {}x{}x{}, rot: {})",
            tilesX, tilesZ, firstPos, facing,
            zone.getName(), (int) zoneSize.x, (int) zoneSize.y, (int) zoneSize.z, rot);

        return new SpawnedGrid(tiles);
    }

    private static SpawnedTile spawnTile(ServerWorld world, BlockPos pos,
                                          Direction facing, int tx, int tz) {
        // Allocate a fresh map ID
        MapIdComponent mapIdComp = world.increaseAndGetMapId();
        int mapId = mapIdComp.id();

        // Create and register a blank MapState
        MapState mapState = MapState.of(
            (byte) 0,  // scale 0 = 1:1
            false,     // no tracking
            world.getRegistryKey()
        );
        world.putMapState(mapIdComp, mapState);

        // Create filled map item with the allocated ID
        ItemStack mapItem = new ItemStack(Items.FILLED_MAP);
        mapItem.set(DataComponentTypes.MAP_ID, mapIdComp);

        // Spawn invisible fixed glow item frame
        GlowItemFrameEntity frame = new GlowItemFrameEntity(world, pos, facing);
        frame.setHeldItemStack(mapItem, false);
        frame.setInvisible(true);
        frame.fixed = true;       // access-widened — won't break or drop
        frame.setInvulnerable(true); // immune to all damage (explosions, creative, etc.)

        world.spawnEntity(frame);
        return new SpawnedTile(tx, tz, mapId, frame);
    }

    /**
     * Remove all item frame entities.
     */
    public static void despawnGrid(SpawnedGrid grid) {
        if (grid == null) return;
        for (var tile : grid.tiles()) {
            if (tile.frame() != null && !tile.frame().isRemoved()) {
                tile.frame().discard();
            }
        }
    }

    /**
     * Remove all glow item frames (holding filled maps) within a zone's bounding box.
     * This cleans up frames orphaned from previous server sessions.
     */
    private static void cleanupOrphanedFrames(ServerWorld world, VisualizationZone zone) {
        Vector3f size = zone.getSize();
        BlockPos origin = zone.getOrigin();
        // Generous bounding box around the zone to catch all possible frame positions
        double margin = Math.max(size.x, Math.max(size.y, size.z)) + 2;
        Box searchBox = new Box(
            origin.getX() - margin, origin.getY() - 1, origin.getZ() - margin,
            origin.getX() + margin, origin.getY() + size.y + 1, origin.getZ() + margin
        );

        List<GlowItemFrameEntity> orphans = world.getEntitiesByClass(
            GlowItemFrameEntity.class, searchBox,
            frame -> frame.isInvisible() && frame.getHeldItemStack().isOf(Items.FILLED_MAP)
        );

        if (!orphans.isEmpty()) {
            LoggerFactory.getLogger("audioviz").info(
                "Cleaning up {} orphaned map frames in zone '{}'", orphans.size(), zone.getName());
            for (GlowItemFrameEntity frame : orphans) {
                frame.discard();
            }
        }
    }

    public record SpawnedTile(int tileX, int tileZ, int mapId, GlowItemFrameEntity frame) {}
    public record SpawnedGrid(List<SpawnedTile> tiles) {}
}
