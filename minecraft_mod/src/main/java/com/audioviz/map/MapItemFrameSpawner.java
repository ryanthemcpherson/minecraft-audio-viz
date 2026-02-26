package com.audioviz.map;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.entity.decoration.GlowItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Spawns a grid of invisible glow item frames holding filled maps.
 * Item frames are Fixed (won't break) and Invisible (only the map content shows).
 * No barrier blocks needed — Fixed item frames don't require a supporting block.
 */
public class MapItemFrameSpawner {

    /**
     * Spawn a grid of invisible glow item frames holding maps.
     *
     * @param world   Server world to spawn in
     * @param origin  Bottom-left corner of the display wall
     * @param facing  Direction the display faces (the side players see from)
     * @param tilesX  Number of tiles horizontally
     * @param tilesZ  Number of tiles vertically (upward)
     * @return        SpawnedGrid with tile info
     */
    public static SpawnedGrid spawnGrid(ServerWorld world, BlockPos origin,
                                         Direction facing, int tilesX, int tilesZ) {
        List<SpawnedTile> tiles = new ArrayList<>();
        // "Screen right" from the viewer's perspective: viewer looks opposite to facing,
        // so their right is counterclockwise from the facing direction.
        Direction right = facing.rotateYCounterclockwise();

        for (int tz = 0; tz < tilesZ; tz++) {
            for (int tx = 0; tx < tilesX; tx++) {
                // Position: offset right for columns, up for rows
                BlockPos tilePos = origin.offset(right, tx).up(tz);

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
                GlowItemFrameEntity frame = new GlowItemFrameEntity(world, tilePos, facing);
                frame.setHeldItemStack(mapItem, false);
                frame.setInvisible(true);
                frame.fixed = true;       // access-widened — won't break or drop
                frame.setInvulnerable(true); // immune to all damage (explosions, creative, etc.)

                world.spawnEntity(frame);
                tiles.add(new SpawnedTile(tx, tz, mapId, frame));
            }
        }

        LoggerFactory.getLogger("audioviz").info(
            "Spawned map grid {}x{} at {} facing {}, mapIds=[{}]",
            tilesX, tilesZ, origin, facing,
            tiles.stream().map(t -> String.valueOf(t.mapId())).collect(Collectors.joining(",")));

        return new SpawnedGrid(tiles);
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

    public record SpawnedTile(int tileX, int tileZ, int mapId, GlowItemFrameEntity frame) {}
    public record SpawnedGrid(List<SpawnedTile> tiles) {}
}
