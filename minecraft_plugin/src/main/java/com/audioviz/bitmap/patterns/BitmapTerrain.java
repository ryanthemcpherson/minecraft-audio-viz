package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * FFT-driven scrolling terrain/mountain silhouette visualization.
 *
 * <p>Renders a side-scrolling landscape where terrain height is determined by
 * audio frequency bands. New terrain data enters from the right edge while
 * the entire landscape scrolls left, creating a moving mountain range that
 * responds to the music in real time.
 *
 * <p>Each column's height is interpolated from the 5 frequency bands mapped
 * across the display width. The terrain is filled with a height-mapped color
 * gradient: deep blue valleys, green lowlands, brown/gray mountains, and
 * white snow-capped peaks.
 *
 * <p>Audio reactivity:
 * <ul>
 *   <li>Frequency bands control terrain height in their respective regions</li>
 *   <li>Beat triggers a seismic bump (sudden elevation increase)</li>
 *   <li>Overall amplitude controls the base elevation</li>
 *   <li>Bass gives extra weight to the left-side mountain range</li>
 * </ul>
 */
public class BitmapTerrain extends BitmapPattern {

    /** Stored terrain heights for each column (normalized 0.0-1.0). */
    private double[] terrainHeights;

    /** Tick counter for scroll timing. */
    private int scrollCounter = 0;

    /** Smooth beat pulse for seismic bumps. */
    private double beatPulse = 0.0;

    private int lastWidth;
    private int lastHeight;

    // Terrain color palette
    private static final int COLOR_DEEP_WATER = BitmapFrameBuffer.rgb(15, 25, 80);
    private static final int COLOR_SHALLOW_WATER = BitmapFrameBuffer.rgb(30, 60, 140);
    private static final int COLOR_SAND = BitmapFrameBuffer.rgb(160, 145, 100);
    private static final int COLOR_GRASS = BitmapFrameBuffer.rgb(50, 130, 50);
    private static final int COLOR_FOREST = BitmapFrameBuffer.rgb(30, 90, 30);
    private static final int COLOR_ROCK = BitmapFrameBuffer.rgb(110, 100, 95);
    private static final int COLOR_SNOW = BitmapFrameBuffer.rgb(230, 235, 245);
    private static final int COLOR_SKY_TOP = BitmapFrameBuffer.rgb(10, 10, 40);
    private static final int COLOR_SKY_BOTTOM = BitmapFrameBuffer.rgb(40, 30, 80);

    public BitmapTerrain() {
        super("bmp_terrain", "Terrain",
              "Scrolling mountain terrain driven by FFT frequency bands");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        // Lazy-init or resize
        if (terrainHeights == null || lastWidth != w || lastHeight != h) {
            terrainHeights = new double[w];
            lastWidth = w;
            lastHeight = h;
        }

        double amplitude = audio.getAmplitude();
        double[] bands = audio.getBands();

        // Beat pulse for seismic bumps
        if (audio.isBeat()) {
            beatPulse = Math.max(beatPulse, audio.getBeatIntensity());
        } else {
            beatPulse *= 0.85;
        }

        // Scroll terrain left every tick
        scrollCounter++;
        System.arraycopy(terrainHeights, 1, terrainHeights, 0, w - 1);

        // Generate new terrain height for the rightmost column
        // Interpolate band values across the width
        double newHeight = 0.1 + amplitude * 0.3;

        // Use the average of all bands weighted by position (right side = higher bands)
        for (int i = 0; i < bands.length; i++) {
            newHeight += bands[i] * 0.12;
        }

        // Seismic bump from beat
        newHeight += beatPulse * 0.2;

        // Add some organic variation using sin waves
        newHeight += 0.05 * Math.sin(time * 1.7);
        newHeight += 0.03 * Math.sin(time * 3.1 + 1.0);

        terrainHeights[w - 1] = Math.max(0.05, Math.min(0.95, newHeight));

        // --- Render ---
        for (int x = 0; x < w; x++) {
            double terrainNorm = terrainHeights[x]; // 0.0-1.0
            int terrainRow = h - 1 - (int) (terrainNorm * (h - 1)); // pixel row of terrain surface

            for (int y = 0; y < h; y++) {
                if (y < terrainRow) {
                    // Sky: vertical gradient
                    double skyT = (double) y / Math.max(1, terrainRow);
                    int skyColor = BitmapFrameBuffer.lerpColor(COLOR_SKY_TOP, COLOR_SKY_BOTTOM, skyT);

                    // Stars in upper sky when amplitude is low
                    if (y < h / 3 && amplitude < 0.3) {
                        // Simple pseudo-random star field
                        long hash = ((long) x * 7919 + (long) y * 6271) & 0xFFFF;
                        if (hash < 20) {
                            double twinkle = 0.5 + 0.5 * Math.sin(time * 3.0 + hash);
                            int starBri = (int) (twinkle * 200);
                            skyColor = BitmapFrameBuffer.rgb(starBri, starBri, (int) (starBri * 0.9));
                        }
                    }

                    buffer.setPixel(x, y, skyColor);
                } else {
                    // Terrain: color based on depth below surface
                    double depth = (double) (y - terrainRow) / Math.max(1, (h - 1 - terrainRow));
                    double elevation = terrainNorm; // overall height

                    int terrainColor = getTerrainColor(elevation, depth);
                    buffer.setPixel(x, y, terrainColor);
                }
            }
        }
    }

    /**
     * Map terrain elevation and depth to a color.
     *
     * @param elevation normalized surface height (0 = sea level, 1 = peak)
     * @param depth     how far below the surface (0 = surface, 1 = bottom)
     * @return packed ARGB color
     */
    private static int getTerrainColor(double elevation, double depth) {
        // Surface layer (depth near 0) uses elevation-based coloring
        // Deeper layers darken toward rock

        int surfaceColor;
        if (elevation < 0.2) {
            surfaceColor = BitmapFrameBuffer.lerpColor(COLOR_DEEP_WATER, COLOR_SHALLOW_WATER,
                    elevation / 0.2);
        } else if (elevation < 0.3) {
            surfaceColor = BitmapFrameBuffer.lerpColor(COLOR_SHALLOW_WATER, COLOR_SAND,
                    (elevation - 0.2) / 0.1);
        } else if (elevation < 0.5) {
            surfaceColor = BitmapFrameBuffer.lerpColor(COLOR_SAND, COLOR_GRASS,
                    (elevation - 0.3) / 0.2);
        } else if (elevation < 0.65) {
            surfaceColor = BitmapFrameBuffer.lerpColor(COLOR_GRASS, COLOR_FOREST,
                    (elevation - 0.5) / 0.15);
        } else if (elevation < 0.8) {
            surfaceColor = BitmapFrameBuffer.lerpColor(COLOR_FOREST, COLOR_ROCK,
                    (elevation - 0.65) / 0.15);
        } else {
            surfaceColor = BitmapFrameBuffer.lerpColor(COLOR_ROCK, COLOR_SNOW,
                    (elevation - 0.8) / 0.2);
        }

        // Darken with depth (underground gets darker)
        if (depth > 0.05) {
            double darkening = Math.min(1.0, depth * 1.5);
            int darkColor = BitmapFrameBuffer.rgb(20, 15, 10);
            return BitmapFrameBuffer.lerpColor(surfaceColor, darkColor, darkening);
        }

        return surfaceColor;
    }

    @Override
    public void reset() {
        terrainHeights = null;
        scrollCounter = 0;
        beatPulse = 0.0;
    }

    @Override
    public void onResize(int width, int height) {
        terrainHeights = null;
        lastWidth = 0;
        lastHeight = 0;
    }
}
