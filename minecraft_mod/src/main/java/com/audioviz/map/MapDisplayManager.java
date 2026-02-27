package com.audioviz.map;

import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collection;

/**
 * Manages a tiled grid of map frame buffers for large displays.
 * Routes ARGB bitmap data to the correct tiles and sends dirty updates.
 *
 * A single map is 128×128 pixels. For larger displays (e.g., 256×128),
 * we tile multiple maps in a grid.
 */
public class MapDisplayManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz.map");
    private int writeFrameCount = 0;
    private int sendUpdateCount = 0;

    private final int displayWidth;
    private final int displayHeight;
    private final int tilesX;
    private final int tilesZ;
    private final MapFrameBuffer[][] tiles;
    private final int[] mapIds; // Minecraft map IDs, allocated on spawn
    /** Pre-allocated sub-region buffers per tile to avoid GC pressure in writeFrame(). */
    private final int[][] tileBuffers;

    public MapDisplayManager(int displayWidth, int displayHeight) {
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        this.tilesX = (displayWidth + MapFrameBuffer.SIZE - 1) / MapFrameBuffer.SIZE;
        this.tilesZ = (displayHeight + MapFrameBuffer.SIZE - 1) / MapFrameBuffer.SIZE;
        this.tiles = new MapFrameBuffer[tilesZ][tilesX];
        this.mapIds = new int[tilesX * tilesZ];
        this.tileBuffers = new int[tilesZ * tilesX][MapFrameBuffer.SIZE * MapFrameBuffer.SIZE];
        for (int z = 0; z < tilesZ; z++) {
            for (int x = 0; x < tilesX; x++) {
                tiles[z][x] = new MapFrameBuffer();
            }
        }
    }

    /**
     * Write a full ARGB frame into the tiled maps.
     * Handles splitting across tile boundaries.
     *
     * If width/height are smaller than displayWidth/displayHeight (i.e. the pattern
     * was rendered at reduced resolution via RENDER_SCALE), nearest-neighbor upscaling
     * is applied so each source pixel maps to a block of display pixels.
     */
    public void writeFrame(int[] argb, int width, int height) {
        boolean needsScale = (width != displayWidth || height != displayHeight);

        for (int tz = 0; tz < tilesZ; tz++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int tileOffsetX = tx * MapFrameBuffer.SIZE;
                // tz=0 is the bottom of the wall, but pixel row 0 is the top of
                // the image. Flip so bottom tiles get bottom pixel rows.
                int tileOffsetZ = (tilesZ - 1 - tz) * MapFrameBuffer.SIZE;

                int regionW = Math.min(MapFrameBuffer.SIZE, displayWidth - tileOffsetX);
                int regionH = Math.min(MapFrameBuffer.SIZE, displayHeight - tileOffsetZ);

                if (regionW <= 0 || regionH <= 0) continue;

                // Reuse pre-allocated buffer instead of allocating per frame
                int[] subRegion = tileBuffers[tz * tilesX + tx];

                if (needsScale) {
                    // Nearest-neighbor upscale: map display pixel → source pixel
                    for (int row = 0; row < regionH; row++) {
                        int srcY = (tileOffsetZ + row) * height / displayHeight;
                        if (srcY >= height) srcY = height - 1;
                        for (int col = 0; col < regionW; col++) {
                            int srcX = (tileOffsetX + col) * width / displayWidth;
                            if (srcX >= width) srcX = width - 1;
                            subRegion[row * regionW + col] = argb[srcY * width + srcX];
                        }
                    }
                } else {
                    // Fast path: 1:1 copy when source matches display
                    for (int row = 0; row < regionH; row++) {
                        System.arraycopy(argb, (tileOffsetZ + row) * width + tileOffsetX,
                                         subRegion, row * regionW, regionW);
                    }
                }

                tiles[tz][tx].writeFromArgb(subRegion, regionW, regionH, 0, 0);
            }
        }

        writeFrameCount++;
        if (writeFrameCount % 100 == 1) {
            int dirtyCount = 0;
            for (int tz = 0; tz < tilesZ; tz++)
                for (int tx = 0; tx < tilesX; tx++)
                    if (tiles[tz][tx].getDirtyRect().isPresent()) dirtyCount++;
            LOGGER.info("MapDisplay writeFrame #{}: src={}x{} disp={}x{} scale={} dirty={}/{} tiles",
                writeFrameCount, width, height, displayWidth, displayHeight, needsScale, dirtyCount, tilesX * tilesZ);
        }
    }

    /** Send dirty tile updates to players. */
    public void sendUpdates(Collection<ServerPlayerEntity> players) {
        int sentCount = 0;
        for (int tz = 0; tz < tilesZ; tz++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int mapId = mapIds[tz * tilesX + tx];
                boolean wasDirty = tiles[tz][tx].getDirtyRect().isPresent();
                MapPacketSender.sendUpdate(mapId, tiles[tz][tx], players);
                if (wasDirty) sentCount++;
            }
        }

        sendUpdateCount++;
        if (sendUpdateCount % 100 == 1) {
            LOGGER.info("MapDisplay sendUpdates #{}: sent={}/{} tiles to {} players",
                sendUpdateCount, sentCount, tilesX * tilesZ, players.size());
        }
    }

    public void setMapId(int tileX, int tileZ, int mapId) {
        if (tileX < 0 || tileX >= tilesX || tileZ < 0 || tileZ >= tilesZ) return;
        mapIds[tileZ * tilesX + tileX] = mapId;
    }

    public int getTileCountX() { return tilesX; }
    public int getTileCountZ() { return tilesZ; }
    public int getTotalTiles() { return tilesX * tilesZ; }
    public MapFrameBuffer getTile(int x, int z) { return tiles[z][x]; }
}
