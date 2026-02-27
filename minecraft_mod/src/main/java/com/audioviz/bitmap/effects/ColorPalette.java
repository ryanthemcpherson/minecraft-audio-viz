package com.audioviz.bitmap.effects;

import com.audioviz.bitmap.BitmapFrameBuffer;

/**
 * Color palette system for bitmap patterns.
 *
 * <p>Patterns output intensity values (0.0-1.0), and the palette maps
 * them to colors. This decouples pattern logic from color theming,
 * letting the VJ hot-swap palettes (warm, cool, neon, monochrome, etc.)
 * without modifying patterns.
 *
 * <p>Each palette is a 256-entry lookup table (LUT) of ARGB colors.
 * Intensity 0.0 maps to index 0, intensity 1.0 maps to index 255.
 */
public class ColorPalette {

    private final String id;
    private final String name;
    private final int[] lut; // 256 entries

    public ColorPalette(String id, String name, int[] lut) {
        if (lut.length != 256) throw new IllegalArgumentException("LUT must be 256 entries");
        this.id = id;
        this.name = name;
        this.lut = lut;
    }

    public String getId() { return id; }
    public String getName() { return name; }

    /**
     * Map a normalized intensity (0.0-1.0) to an ARGB color.
     */
    public int map(double intensity) {
        int idx = (int) (Math.max(0, Math.min(1, intensity)) * 255);
        return lut[idx];
    }

    /**
     * Map a normalized intensity with interpolation between LUT entries.
     */
    public int mapSmooth(double intensity) {
        double scaled = Math.max(0, Math.min(1, intensity)) * 254;
        int lo = (int) scaled;
        int hi = Math.min(255, lo + 1);
        float frac = (float) (scaled - lo);
        return BitmapFrameBuffer.lerpColor(lut[lo], lut[hi], frac);
    }

    /**
     * Apply this palette to an entire buffer, treating existing pixel
     * brightness as intensity.
     */
    public void applyToBuffer(BitmapFrameBuffer buffer) {
        int[] pixels = buffer.getRawPixels();
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            // Luminance as intensity
            double intensity = (r * 0.299 + g * 0.587 + b * 0.114) / 255.0;
            pixels[i] = mapSmooth(intensity);
        }
    }

    /**
     * Get the raw LUT (for serialization).
     */
    public int[] getLut() {
        return lut.clone();
    }

    // ========== Built-in Palette Generators ==========

    /**
     * Create a gradient palette between N color stops, evenly spaced.
     */
    public static ColorPalette fromGradient(String id, String name, int... colors) {
        if (colors.length < 2) throw new IllegalArgumentException("Need at least 2 colors");
        int[] lut = new int[256];
        int segments = colors.length - 1;
        for (int i = 0; i < 256; i++) {
            double pos = (double) i / 255 * segments;
            int seg = Math.min(segments - 1, (int) pos);
            float t = (float) (pos - seg);
            lut[i] = BitmapFrameBuffer.lerpColor(colors[seg], colors[seg + 1], t);
        }
        return new ColorPalette(id, name, lut);
    }

    // ========== Built-in Palettes ==========

    /** Classic spectrum: purple → blue → cyan → green → yellow → red */
    public static final ColorPalette SPECTRUM = fromGradient("spectrum", "Spectrum",
        0xFF000033, 0xFF0000FF, 0xFF00FFFF, 0xFF00FF00, 0xFFFFFF00, 0xFFFF0000);

    /** Warm: black → red → orange → yellow → white */
    public static final ColorPalette WARM = fromGradient("warm", "Warm",
        0xFF000000, 0xFF660000, 0xFFCC3300, 0xFFFF9900, 0xFFFFFF00, 0xFFFFFFFF);

    /** Cool: black → deep blue → cyan → white */
    public static final ColorPalette COOL = fromGradient("cool", "Cool",
        0xFF000000, 0xFF000066, 0xFF0033CC, 0xFF0099FF, 0xFF66FFFF, 0xFFFFFFFF);

    /** Neon: dark → magenta → electric blue → neon green → hot pink */
    public static final ColorPalette NEON = fromGradient("neon", "Neon",
        0xFF000000, 0xFFFF00FF, 0xFF0066FF, 0xFF00FF66, 0xFFFF0066, 0xFFFFFFFF);

    /** Monochrome: black → white */
    public static final ColorPalette MONO = fromGradient("mono", "Monochrome",
        0xFF000000, 0xFFFFFFFF);

    /** Lava: black → dark red → orange → yellow → white */
    public static final ColorPalette LAVA = fromGradient("lava", "Lava",
        0xFF000000, 0xFF330000, 0xFF990000, 0xFFFF3300, 0xFFFF9900, 0xFFFFFF66);

    /** Ocean: deep navy → teal → aqua → white foam */
    public static final ColorPalette OCEAN = fromGradient("ocean", "Ocean",
        0xFF000022, 0xFF003366, 0xFF006688, 0xFF00AAAA, 0xFF66DDCC, 0xFFAAFFEE);

    /** Sunset: deep purple → magenta → orange → gold */
    public static final ColorPalette SUNSET = fromGradient("sunset", "Sunset",
        0xFF110022, 0xFF440066, 0xFF990044, 0xFFCC3300, 0xFFFF6600, 0xFFFFCC00);

    /** Forest: black → dark green → emerald → lime → white */
    public static final ColorPalette FOREST = fromGradient("forest", "Forest",
        0xFF000000, 0xFF003300, 0xFF006600, 0xFF00AA00, 0xFF66FF00, 0xFFCCFFCC);

    /** Cyberpunk: black → purple → hot pink → cyan → white */
    public static final ColorPalette CYBERPUNK = fromGradient("cyberpunk", "Cyberpunk",
        0xFF000000, 0xFF330066, 0xFFFF0099, 0xFF00CCFF, 0xFF00FFCC, 0xFFFFFFFF);

    /** All built-in palettes. */
    public static final ColorPalette[] BUILT_IN = {
        SPECTRUM, WARM, COOL, NEON, MONO, LAVA, OCEAN, SUNSET, FOREST, CYBERPUNK
    };
}
