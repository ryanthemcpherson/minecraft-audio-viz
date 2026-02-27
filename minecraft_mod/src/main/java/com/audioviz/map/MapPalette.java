package com.audioviz.map;

/**
 * Converts ARGB colors to Minecraft's 248-entry map color palette.
 * Uses a precomputed lookup table (quantized to 6 bits per channel)
 * for O(1) conversion at runtime.
 *
 * 62 base colors × 4 shades = 248 entries.
 * Index = base_id * 4 + shade (0..3)
 * Shade multipliers: 0→180/255, 1→220/255, 2→255/255, 3→135/255
 */
public final class MapPalette {

    private static final int[] SHADE_MULTIPLIERS = {180, 220, 255, 135};

    // Base colors: [id] = {r, g, b}
    // Source: https://minecraft.wiki/w/Map_item_format
    private static final int[][] BASE_COLORS = {
        /* 0  NONE      */ {0, 0, 0},        // transparent, skip
        /* 1  GRASS     */ {127, 178, 56},
        /* 2  SAND      */ {247, 233, 163},
        /* 3  WOOL      */ {199, 199, 199},
        /* 4  FIRE      */ {255, 0, 0},
        /* 5  ICE       */ {160, 160, 255},
        /* 6  METAL     */ {167, 167, 167},
        /* 7  PLANT     */ {0, 124, 0},
        /* 8  SNOW      */ {255, 255, 255},
        /* 9  CLAY      */ {164, 168, 184},
        /* 10 DIRT      */ {151, 109, 77},
        /* 11 STONE     */ {112, 112, 112},
        /* 12 WATER     */ {64, 64, 255},
        /* 13 WOOD      */ {143, 119, 72},
        /* 14 QUARTZ    */ {255, 252, 245},
        /* 15 ORANGE    */ {216, 127, 51},
        /* 16 MAGENTA   */ {178, 76, 216},
        /* 17 LIGHT_BLUE*/ {102, 153, 216},
        /* 18 YELLOW    */ {229, 229, 51},
        /* 19 LIME      */ {127, 204, 25},
        /* 20 PINK      */ {242, 127, 165},
        /* 21 GRAY      */ {76, 76, 76},
        /* 22 LIGHT_GRAY*/ {153, 153, 153},
        /* 23 CYAN      */ {76, 127, 153},
        /* 24 PURPLE    */ {127, 63, 178},
        /* 25 BLUE      */ {51, 76, 178},
        /* 26 BROWN     */ {102, 76, 51},
        /* 27 GREEN     */ {102, 127, 51},
        /* 28 RED       */ {153, 51, 51},
        /* 29 BLACK     */ {25, 25, 25},
        /* 30 GOLD      */ {250, 238, 77},
        /* 31 DIAMOND   */ {92, 219, 213},
        /* 32 LAPIS     */ {74, 128, 255},
        /* 33 EMERALD   */ {0, 217, 58},
        /* 34 PODZOL    */ {129, 86, 49},
        /* 35 NETHER    */ {112, 2, 0},
        /* 36 TERR_WHITE*/ {209, 177, 161},
        /* 37 TERR_ORNGE*/ {159, 82, 36},
        /* 38 TERR_MGNTA*/ {149, 87, 108},
        /* 39 TERR_LTBLU*/ {112, 108, 138},
        /* 40 TERR_YELLW*/ {186, 133, 36},
        /* 41 TERR_LIME */ {103, 117, 53},
        /* 42 TERR_PINK */ {160, 77, 78},
        /* 43 TERR_GRAY */ {57, 41, 35},
        /* 44 TERR_LTGRY*/ {135, 107, 98},
        /* 45 TERR_CYAN */ {87, 92, 92},
        /* 46 TERR_PURPL*/ {122, 73, 88},
        /* 47 TERR_BLUE */ {76, 62, 92},
        /* 48 TERR_BROWN*/ {76, 50, 35},
        /* 49 TERR_GREEN*/ {76, 82, 42},
        /* 50 TERR_RED  */ {142, 60, 46},
        /* 51 TERR_BLACK*/ {37, 22, 16},
        /* 52 CRIMSON_NY*/ {189, 48, 49},
        /* 53 CRIMSON_ST*/ {148, 63, 97},
        /* 54 CRIMSON_HY*/ {92, 25, 29},
        /* 55 WARPED_NYL*/ {22, 126, 134},
        /* 56 WARPED_STE*/ {58, 142, 140},
        /* 57 WARPED_HYP*/ {86, 44, 62},
        /* 58 WARPED_WRT*/ {20, 180, 133},
        /* 59 DEEPSLATE */ {100, 100, 100},
        /* 60 RAW_IRON  */ {216, 175, 147},
        /* 61 LICHEN    */ {127, 167, 150},
    };

    // Quantized lookup table: 64×64×64 = 262,144 bytes
    private static final byte[] LOOKUP = new byte[64 * 64 * 64];

    // Full palette: 248 entries of {r, g, b}
    private static final int[][] PALETTE = new int[248][3];

    static {
        // Build full palette from base colors × shade multipliers
        for (int base = 1; base < BASE_COLORS.length; base++) {
            for (int shade = 0; shade < 4; shade++) {
                int idx = base * 4 + shade;
                int mult = SHADE_MULTIPLIERS[shade];
                PALETTE[idx][0] = BASE_COLORS[base][0] * mult / 255;
                PALETTE[idx][1] = BASE_COLORS[base][1] * mult / 255;
                PALETTE[idx][2] = BASE_COLORS[base][2] * mult / 255;
            }
        }

        // Precompute quantized lookup table
        for (int r6 = 0; r6 < 64; r6++) {
            for (int g6 = 0; g6 < 64; g6++) {
                for (int b6 = 0; b6 < 64; b6++) {
                    int r = (r6 << 2) | 2; // center of quantization bin
                    int g = (g6 << 2) | 2;
                    int b = (b6 << 2) | 2;
                    LOOKUP[(r6 << 12) | (g6 << 6) | b6] = findNearest(r, g, b);
                }
            }
        }
    }

    private static byte findNearest(int r, int g, int b) {
        int bestIdx = 4; // skip transparent (0-3)
        int bestDist = Integer.MAX_VALUE;
        for (int i = 4; i < 248; i++) {
            int dr = r - PALETTE[i][0];
            int dg = g - PALETTE[i][1];
            int db = b - PALETTE[i][2];
            // Weighted Euclidean (human perception: green > red > blue)
            int dist = 2 * dr * dr + 4 * dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                bestIdx = i;
            }
        }
        return (byte) bestIdx;
    }

    /** O(1) RGB to map color lookup. */
    public static byte rgbToMapColor(int r, int g, int b) {
        return LOOKUP[((r >> 2) << 12) | ((g >> 2) << 6) | (b >> 2)];
    }

    /** ARGB to map color. Returns 0 (transparent) if alpha < 128. */
    public static byte argbToMapColor(int argb) {
        if (((argb >> 24) & 0xFF) < 128) return 0;
        return rgbToMapColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
    }

    /** Brute-force search for testing. */
    public static byte rgbToMapColorBruteForce(int r, int g, int b) {
        return findNearest(r, g, b);
    }

    private MapPalette() {} // utility class
}
