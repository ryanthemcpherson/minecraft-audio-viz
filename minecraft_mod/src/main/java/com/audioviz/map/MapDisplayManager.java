package com.audioviz.map;

import net.minecraft.server.network.ServerPlayerEntity;
import java.util.Collection;

/**
 * Manages a tiled grid of map frame buffers for large displays.
 * Routes ARGB bitmap data to the correct tiles and sends dirty updates.
 *
 * A single map is 128×128 pixels. For larger displays (e.g., 256×128),
 * we tile multiple maps in a grid.
 */
public class MapDisplayManager {
    private final int displayWidth;
    private final int displayHeight;
    private final int tilesX;
    private final int tilesZ;
    private final MapFrameBuffer[][] tiles;
    private final int[] mapIds; // Minecraft map IDs, allocated on spawn

    public MapDisplayManager(int displayWidth, int displayHeight) {
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        this.tilesX = (displayWidth + MapFrameBuffer.SIZE - 1) / MapFrameBuffer.SIZE;
        this.tilesZ = (displayHeight + MapFrameBuffer.SIZE - 1) / MapFrameBuffer.SIZE;
        this.tiles = new MapFrameBuffer[tilesZ][tilesX];
        this.mapIds = new int[tilesX * tilesZ];
        for (int z = 0; z < tilesZ; z++) {
            for (int x = 0; x < tilesX; x++) {
                tiles[z][x] = new MapFrameBuffer();
            }
        }
    }

    /**
     * Write a full ARGB frame into the tiled maps.
     * Handles splitting across tile boundaries.
     */
    public void writeFrame(int[] argb, int width, int height) {
        for (int tz = 0; tz < tilesZ; tz++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int tileOffsetX = tx * MapFrameBuffer.SIZE;
                int tileOffsetZ = tz * MapFrameBuffer.SIZE;

                int regionW = Math.min(MapFrameBuffer.SIZE, width - tileOffsetX);
                int regionH = Math.min(MapFrameBuffer.SIZE, height - tileOffsetZ);

                if (regionW <= 0 || regionH <= 0) continue;

                // Extract the sub-region from the source ARGB array
                int[] subRegion = new int[regionW * regionH];
                for (int row = 0; row < regionH; row++) {
                    System.arraycopy(argb, (tileOffsetZ + row) * width + tileOffsetX,
                                     subRegion, row * regionW, regionW);
                }

                tiles[tz][tx].writeFromArgb(subRegion, regionW, regionH, 0, 0);
            }
        }
    }

    /** Send dirty updates for all tiles to players. */
    public void sendUpdates(Collection<ServerPlayerEntity> players) {
        for (int tz = 0; tz < tilesZ; tz++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int mapId = mapIds[tz * tilesX + tx];
                MapPacketSender.sendDirtyUpdate(mapId, tiles[tz][tx], players);
            }
        }
    }

    public void setMapId(int tileX, int tileZ, int mapId) {
        mapIds[tileZ * tilesX + tileX] = mapId;
    }

    public int getTileCountX() { return tilesX; }
    public int getTileCountZ() { return tilesZ; }
    public int getTotalTiles() { return tilesX * tilesZ; }
    public MapFrameBuffer getTile(int x, int z) { return tiles[z][x]; }
}
