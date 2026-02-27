package com.audioviz.map;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Spawns a grid of invisible item frames holding filled maps.
 * Very few entities (1 per 128×128 tile) compared to the Paper plugin's
 * 1 TextDisplay per pixel approach.
 */
public class MapItemFrameSpawner {

    /**
     * Spawn a grid of invisible item frames holding blank maps.
     *
     * @param world   Server world to spawn in
     * @param origin  Bottom-left corner of the display wall
     * @param facing  Direction the display faces (e.g., Direction.NORTH)
     * @param tilesX  Number of tiles horizontally
     * @param tilesZ  Number of tiles vertically
     * @return        List of spawned tiles with their map IDs
     */
    public static List<SpawnedTile> spawnGrid(ServerWorld world, BlockPos origin,
                                               Direction facing, int tilesX, int tilesZ) {
        List<SpawnedTile> result = new ArrayList<>();

        Direction right = facing.rotateYClockwise();

        for (int tz = 0; tz < tilesZ; tz++) {
            for (int tx = 0; tx < tilesX; tx++) {
                BlockPos pos = origin
                    .offset(right, tx)
                    .offset(Direction.UP, tz);

                // Create a filled map (allocates a new map ID automatically)
                ItemStack mapItem = FilledMapItem.createMap(
                    world, pos.getX(), pos.getZ(), (byte) 0, false, false
                );
                MapIdComponent mapIdComp = mapItem.get(DataComponentTypes.MAP_ID);
                int mapId = mapIdComp.id();

                // Spawn invisible item frame (constructor sets facing direction)
                ItemFrameEntity frame = new ItemFrameEntity(world, pos, facing);
                frame.setHeldItemStack(mapItem);
                frame.setInvisible(true);
                frame.setSilent(true);
                world.spawnEntity(frame);

                result.add(new SpawnedTile(tx, tz, mapId, frame));
            }
        }

        return result;
    }

    /**
     * Remove all item frames from a previously spawned grid.
     */
    public static void despawnGrid(List<SpawnedTile> tiles) {
        if (tiles == null) return;
        for (SpawnedTile tile : tiles) {
            if (tile.frame().isAlive()) {
                tile.frame().discard();
            }
        }
    }

    public record SpawnedTile(int tileX, int tileZ, int mapId, ItemFrameEntity frame) {}
}
