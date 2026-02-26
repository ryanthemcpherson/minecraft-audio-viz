package com.audioviz.map;

import java.util.Optional;

/**
 * 128x128 byte buffer representing one Minecraft map's pixel data.
 * Tracks dirty rectangle for partial packet updates.
 */
public class MapFrameBuffer {
    public static final int SIZE = 128;
    private final byte[] pixels = new byte[SIZE * SIZE]; // row-major

    // Dirty rect tracking
    private int dirtyMinX = Integer.MAX_VALUE, dirtyMinZ = Integer.MAX_VALUE;
    private int dirtyMaxX = Integer.MIN_VALUE, dirtyMaxZ = Integer.MIN_VALUE;
    private boolean dirty = false;

    public record DirtyRect(int x, int z, int width, int height) {}

    public void setPixel(int x, int z, byte color) {
        if (x < 0 || x >= SIZE || z < 0 || z >= SIZE) return;
        int idx = z * SIZE + x;
        if (pixels[idx] == color) return; // no change
        pixels[idx] = color;
        markDirty(x, z);
    }

    public byte getPixel(int x, int z) {
        if (x < 0 || x >= SIZE || z < 0 || z >= SIZE) return 0;
        return pixels[z * SIZE + x];
    }

    /**
     * Bulk-write ARGB pixels into this map buffer with palette conversion.
     * Writes a width×height block starting at (offsetX, offsetZ).
     */
    public void writeFromArgb(int[] argb, int width, int height, int offsetX, int offsetZ) {
        for (int y = 0; y < height && (offsetZ + y) < SIZE; y++) {
            for (int x = 0; x < width && (offsetX + x) < SIZE; x++) {
                int px = offsetX + x;
                int pz = offsetZ + y;
                byte color = MapPalette.argbToMapColor(argb[y * width + x]);
                int idx = pz * SIZE + px;
                if (pixels[idx] != color) {
                    pixels[idx] = color;
                    markDirty(px, pz);
                }
            }
        }
    }

    private void markDirty(int x, int z) {
        dirty = true;
        dirtyMinX = Math.min(dirtyMinX, x);
        dirtyMinZ = Math.min(dirtyMinZ, z);
        dirtyMaxX = Math.max(dirtyMaxX, x);
        dirtyMaxZ = Math.max(dirtyMaxZ, z);
    }

    public Optional<DirtyRect> getDirtyRect() {
        if (!dirty) return Optional.empty();
        return Optional.of(new DirtyRect(
            dirtyMinX, dirtyMinZ,
            dirtyMaxX - dirtyMinX + 1,
            dirtyMaxZ - dirtyMinZ + 1
        ));
    }

    /**
     * Extract the dirty region's pixel data for the map packet.
     * Returns row-major order: data[x + z * width] matching
     * MapState.UpdateData.setColorsTo() indexing.
     */
    public byte[] extractDirtyData() {
        if (!dirty) return new byte[0];
        int w = dirtyMaxX - dirtyMinX + 1;
        int h = dirtyMaxZ - dirtyMinZ + 1;
        byte[] data = new byte[w * h];
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                data[x + z * w] = pixels[(dirtyMinZ + z) * SIZE + (dirtyMinX + x)];
            }
        }
        return data;
    }

    public void clearDirty() {
        dirty = false;
        dirtyMinX = Integer.MAX_VALUE;
        dirtyMinZ = Integer.MAX_VALUE;
        dirtyMaxX = Integer.MIN_VALUE;
        dirtyMaxZ = Integer.MIN_VALUE;
    }

    /**
     * Extract the full 128×128 frame for MapState.UpdateData.
     * UpdateData.setColorsTo indexes as colors[x + z * width] — same row-major
     * layout as our buffer (pixels[z * SIZE + x]), so direct copy works.
     */
    public byte[] extractFullFrame() {
        byte[] data = new byte[SIZE * SIZE];
        System.arraycopy(pixels, 0, data, 0, SIZE * SIZE);
        return data;
    }

    public byte[] getRawPixels() {
        return pixels;
    }
}
