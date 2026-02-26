package com.audioviz.render;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/**
 * Maps ARGB pixel colors to the nearest Minecraft block state.
 * Uses an RGB565 lookup cache (65536 entries) for O(1) per-pixel mapping.
 *
 * Palette: 16 concrete + 16 terracotta = 32 distinct block colors.
 * Concrete for saturated hues, terracotta for muted/earthy tones.
 */
public class BlockColorMapper {

    private record PaletteEntry(BlockState state, int r, int g, int b) {}

    private static final PaletteEntry[] PALETTE = {
        // Concrete (saturated, high contrast — best for LED wall aesthetic)
        entry(Blocks.WHITE_CONCRETE.getDefaultState(),      207, 213, 214),
        entry(Blocks.ORANGE_CONCRETE.getDefaultState(),     224,  97,   1),
        entry(Blocks.MAGENTA_CONCRETE.getDefaultState(),    169,  48, 159),
        entry(Blocks.LIGHT_BLUE_CONCRETE.getDefaultState(),  35, 137, 198),
        entry(Blocks.YELLOW_CONCRETE.getDefaultState(),     241, 175,  21),
        entry(Blocks.LIME_CONCRETE.getDefaultState(),        94, 169,  25),
        entry(Blocks.PINK_CONCRETE.getDefaultState(),       214, 101, 143),
        entry(Blocks.GRAY_CONCRETE.getDefaultState(),        55,  58,  62),
        entry(Blocks.LIGHT_GRAY_CONCRETE.getDefaultState(), 125, 125, 115),
        entry(Blocks.CYAN_CONCRETE.getDefaultState(),        21, 119, 136),
        entry(Blocks.PURPLE_CONCRETE.getDefaultState(),     100,  32, 156),
        entry(Blocks.BLUE_CONCRETE.getDefaultState(),        45,  47, 143),
        entry(Blocks.BROWN_CONCRETE.getDefaultState(),       96,  60,  32),
        entry(Blocks.GREEN_CONCRETE.getDefaultState(),       73,  91,  36),
        entry(Blocks.RED_CONCRETE.getDefaultState(),        142,  33,  33),
        entry(Blocks.BLACK_CONCRETE.getDefaultState(),        8,  10,  15),

        // Terracotta (muted earthtones — fills gaps in color space)
        entry(Blocks.WHITE_TERRACOTTA.getDefaultState(),    210, 178, 161),
        entry(Blocks.ORANGE_TERRACOTTA.getDefaultState(),   162,  84,  38),
        entry(Blocks.MAGENTA_TERRACOTTA.getDefaultState(),  149,  88, 109),
        entry(Blocks.LIGHT_BLUE_TERRACOTTA.getDefaultState(), 113, 109, 138),
        entry(Blocks.YELLOW_TERRACOTTA.getDefaultState(),   186, 133,  35),
        entry(Blocks.LIME_TERRACOTTA.getDefaultState(),     103, 117,  53),
        entry(Blocks.PINK_TERRACOTTA.getDefaultState(),     162,  78,  79),
        entry(Blocks.GRAY_TERRACOTTA.getDefaultState(),      58,  42,  36),
        entry(Blocks.LIGHT_GRAY_TERRACOTTA.getDefaultState(), 135, 107,  98),
        entry(Blocks.CYAN_TERRACOTTA.getDefaultState(),      87,  92,  92),
        entry(Blocks.PURPLE_TERRACOTTA.getDefaultState(),   118,  70,  86),
        entry(Blocks.BLUE_TERRACOTTA.getDefaultState(),      74,  60,  91),
        entry(Blocks.BROWN_TERRACOTTA.getDefaultState(),     77,  51,  36),
        entry(Blocks.GREEN_TERRACOTTA.getDefaultState(),     76,  83,  42),
        entry(Blocks.RED_TERRACOTTA.getDefaultState(),      143,  61,  47),
        entry(Blocks.BLACK_TERRACOTTA.getDefaultState(),     37,  23,  16),
    };

    /**
     * RGB565 lookup cache: 5 bits R, 6 bits G, 5 bits B = 65536 entries.
     * Each entry is the palette index of the nearest color match.
     * Built once at class load, then all lookups are O(1).
     */
    private static final byte[] RGB565_CACHE = new byte[65536];

    static {
        for (int i = 0; i < 65536; i++) {
            int r5 = (i >> 11) & 0x1F;
            int g6 = (i >> 5) & 0x3F;
            int b5 = i & 0x1F;
            // Expand to 8-bit
            int r = (r5 << 3) | (r5 >> 2);
            int g = (g6 << 2) | (g6 >> 4);
            int b = (b5 << 3) | (b5 >> 2);
            RGB565_CACHE[i] = (byte) findNearestIndex(r, g, b);
        }
    }

    private static int findNearestIndex(int r, int g, int b) {
        int bestIdx = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < PALETTE.length; i++) {
            int dr = r - PALETTE[i].r;
            int dg = g - PALETTE[i].g;
            int db = b - PALETTE[i].b;
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    /** Map an ARGB color to the nearest Minecraft block state. */
    public static BlockState mapColor(int argb) {
        int a = (argb >> 24) & 0xFF;
        if (a < 128) return Blocks.BLACK_CONCRETE.getDefaultState();

        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int rgb565 = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);
        return PALETTE[RGB565_CACHE[rgb565] & 0xFF].state;
    }

    public static int getPaletteSize() {
        return PALETTE.length;
    }

    private static PaletteEntry entry(BlockState state, int r, int g, int b) {
        return new PaletteEntry(state, r, g, b);
    }
}
