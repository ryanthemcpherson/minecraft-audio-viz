package com.audioviz.bitmap;

/*
 * ARGB pixel frame buffer for bitmap LED wall rendering.
 *
 * Part of MCAV's bitmap display system, which adapts the text-display-as-pixel
 * technique pioneered by TheCymaera:
 *   https://github.com/TheCymaera/minecraft-text-display-experiments
 */

import org.bukkit.Color;

/**
 * 2D ARGB pixel buffer for bitmap-mode visualization.
 * Analogous to TheCymaera's Grid&lt;Color&gt; but optimized for real-time audio viz:
 * stores raw int ARGB to avoid object allocation per pixel per frame.
 *
 * <p>Drawing primitives (fill, gradient, line, circle) are provided so that
 * both the server-side pattern engine and the WebSocket {@code bitmap_frame}
 * protocol can write into the same buffer format.
 *
 * <p>Thread safety: not thread-safe. Callers must synchronize externally or
 * use double-buffering (write to back buffer, swap reference on main thread).
 */
public class BitmapFrameBuffer {

    private final int width;
    private final int height;
    private final int[] pixels; // row-major: index = y * width + x

    public BitmapFrameBuffer(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive: " + width + "x" + height);
        }
        if ((long) width * height > 16_384) {
            throw new IllegalArgumentException("Bitmap too large: " + width + "x" + height +
                " = " + (width * height) + " pixels (max 16384)");
        }
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getPixelCount() { return pixels.length; }

    // ========== Pixel Access ==========

    /**
     * Get pixel as packed ARGB int.
     */
    public int getPixel(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) return 0;
        return pixels[y * width + x];
    }

    /**
     * Set pixel from packed ARGB int.
     */
    public void setPixel(int x, int y, int argb) {
        if (x < 0 || x >= width || y < 0 || y >= height) return;
        pixels[y * width + x] = argb;
    }

    /**
     * Set pixel from RGBA components (0-255 each).
     */
    public void setPixel(int x, int y, int r, int g, int b, int a) {
        setPixel(x, y, packARGB(a, r, g, b));
    }

    /**
     * Set pixel from Bukkit Color.
     */
    public void setPixel(int x, int y, Color color) {
        // Color.asARGB() returns 0xAARRGGBB
        setPixel(x, y, color.asARGB());
    }

    /**
     * Get pixel by flat index (row-major).
     */
    public int getPixelFlat(int index) {
        if (index < 0 || index >= pixels.length) return 0;
        return pixels[index];
    }

    /**
     * Get the raw pixel array (row-major ARGB). Returned by reference for performance
     * in the hot render path — do NOT modify outside the frame update cycle.
     */
    public int[] getRawPixels() {
        return pixels;
    }

    /**
     * Convert pixel to Bukkit Color.
     */
    public Color getPixelColor(int x, int y) {
        int argb = getPixel(x, y);
        return Color.fromARGB(
            (argb >> 24) & 0xFF,
            (argb >> 16) & 0xFF,
            (argb >> 8) & 0xFF,
            argb & 0xFF
        );
    }

    // ========== Drawing Primitives ==========

    /**
     * Clear entire buffer to a single color.
     */
    public void clear(int argb) {
        java.util.Arrays.fill(pixels, argb);
    }

    /**
     * Clear to transparent black.
     */
    public void clear() {
        clear(0x00000000);
    }

    /**
     * Fill a rectangular region.
     */
    public void fillRect(int x, int y, int w, int h, int argb) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(width, x + w);
        int y1 = Math.min(height, y + h);
        for (int py = y0; py < y1; py++) {
            int rowOffset = py * width;
            for (int px = x0; px < x1; px++) {
                pixels[rowOffset + px] = argb;
            }
        }
    }

    /**
     * Fill entire buffer.
     */
    public void fill(int argb) {
        java.util.Arrays.fill(pixels, argb);
    }

    /**
     * Draw a horizontal gradient across the full width at row y.
     */
    public void drawHorizontalGradient(int y, int leftARGB, int rightARGB) {
        if (y < 0 || y >= height) return;
        int rowOffset = y * width;
        for (int x = 0; x < width; x++) {
            float t = (float) x / (width - 1);
            pixels[rowOffset + x] = lerpColor(leftARGB, rightARGB, t);
        }
    }

    /**
     * Draw a vertical gradient across the full height at column x.
     */
    public void drawVerticalGradient(int x, int topARGB, int bottomARGB) {
        if (x < 0 || x >= width) return;
        for (int y = 0; y < height; y++) {
            float t = (float) y / (height - 1);
            pixels[y * width + x] = lerpColor(topARGB, bottomARGB, t);
        }
    }

    /**
     * Fill a column from bottom up to a normalized height (0.0-1.0).
     * Used for spectrum bar rendering.
     *
     * @param col        column index
     * @param normHeight 0.0 = empty, 1.0 = full height
     * @param argb       fill color
     */
    public void fillColumn(int col, double normHeight, int argb) {
        if (col < 0 || col >= width) return;
        int fillRows = (int) (normHeight * height);
        fillRows = Math.max(0, Math.min(height, fillRows));
        for (int row = height - fillRows; row < height; row++) {
            pixels[row * width + col] = argb;
        }
    }

    /**
     * Fill a column with a vertical gradient from bottom to top.
     * Height is normalized (0.0-1.0).
     */
    public void fillColumnGradient(int col, double normHeight, int bottomARGB, int topARGB) {
        if (col < 0 || col >= width) return;
        int fillRows = (int) (normHeight * height);
        fillRows = Math.max(0, Math.min(height, fillRows));
        if (fillRows == 0) return;

        int startRow = height - fillRows;
        for (int row = startRow; row < height; row++) {
            float t = (float) (row - startRow) / fillRows;
            pixels[row * width + col] = lerpColor(topARGB, bottomARGB, t);
        }
    }

    /**
     * Draw a filled circle (clipped to buffer bounds).
     */
    public void fillCircle(int cx, int cy, int radius, int argb) {
        int r2 = radius * radius;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dy * dy <= r2) {
                    setPixel(cx + dx, cy + dy, argb);
                }
            }
        }
    }

    /**
     * Draw a ring (hollow circle).
     */
    public void drawRing(int cx, int cy, int radius, int thickness, int argb) {
        int outerR2 = radius * radius;
        int innerR2 = Math.max(0, (radius - thickness) * (radius - thickness));
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int d2 = dx * dx + dy * dy;
                if (d2 <= outerR2 && d2 >= innerR2) {
                    setPixel(cx + dx, cy + dy, argb);
                }
            }
        }
    }

    /**
     * Scroll all rows down by 1 (row 0 becomes empty).
     * Used for waterfall/spectrogram effects.
     */
    public void scrollDown() {
        System.arraycopy(pixels, 0, pixels, width, (height - 1) * width);
        java.util.Arrays.fill(pixels, 0, width, 0x00000000);
    }

    /**
     * Scroll all rows up by 1 (last row becomes empty).
     */
    public void scrollUp() {
        System.arraycopy(pixels, width, pixels, 0, (height - 1) * width);
        java.util.Arrays.fill(pixels, (height - 1) * width, pixels.length, 0x00000000);
    }

    /**
     * Scroll all columns left by 1 (rightmost column becomes empty).
     */
    public void scrollLeft() {
        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            System.arraycopy(pixels, rowOffset + 1, pixels, rowOffset, width - 1);
            pixels[rowOffset + width - 1] = 0x00000000;
        }
    }

    /**
     * Write an entire row of pixels (used for spectrogram: one FFT frame = one row).
     */
    public void setRow(int y, int[] rowPixels) {
        if (y < 0 || y >= height) return;
        int count = Math.min(width, rowPixels.length);
        System.arraycopy(rowPixels, 0, pixels, y * width, count);
    }

    /**
     * Bulk-load the entire pixel buffer from a flat ARGB array.
     * Used by the {@code bitmap_frame} WebSocket message handler.
     *
     * @param data row-major ARGB array, must be length == width * height
     */
    public void loadPixels(int[] data) {
        if (data.length != pixels.length) {
            throw new IllegalArgumentException("Data length " + data.length +
                " does not match buffer size " + pixels.length);
        }
        System.arraycopy(data, 0, pixels, 0, pixels.length);
    }

    /**
     * Apply a global brightness multiplier (0.0 = black, 1.0 = unchanged).
     * Useful for fade-to-black or amplitude-reactive dimming.
     */
    public void applyBrightness(double multiplier) {
        if (multiplier == 1.0) return;
        multiplier = Math.max(0.0, multiplier);
        for (int i = 0; i < pixels.length; i++) {
            int argb = pixels[i];
            int a = (argb >> 24) & 0xFF;
            int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * multiplier));
            int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * multiplier));
            int b = Math.min(255, (int) ((argb & 0xFF) * multiplier));
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    // ========== Color Utilities ==========

    /**
     * Pack ARGB components into a single int.
     */
    public static int packARGB(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    /**
     * Create opaque color from RGB.
     */
    public static int rgb(int r, int g, int b) {
        return packARGB(255, r, g, b);
    }

    /**
     * Linearly interpolate between two ARGB colors.
     */
    /** Convenience overload accepting double. */
    public static int lerpColor(int c1, int c2, double t) {
        return lerpColor(c1, c2, (float) t);
    }

    public static int lerpColor(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        float invT = 1f - t;
        int a = (int) (((c1 >> 24) & 0xFF) * invT + ((c2 >> 24) & 0xFF) * t);
        int r = (int) (((c1 >> 16) & 0xFF) * invT + ((c2 >> 16) & 0xFF) * t);
        int g = (int) (((c1 >> 8) & 0xFF) * invT + ((c2 >> 8) & 0xFF) * t);
        int b = (int) ((c1 & 0xFF) * invT + (c2 & 0xFF) * t);
        return packARGB(a, r, g, b);
    }

    /**
     * Create a color from HSB (hue 0-360, saturation 0-1, brightness 0-1).
     * Returns opaque ARGB.
     */
    public static int fromHSB(float hue, float saturation, float brightness) {
        int rgb = java.awt.Color.HSBtoRGB(hue / 360f, saturation, brightness);
        return 0xFF000000 | (rgb & 0x00FFFFFF); // Force opaque
    }

    /**
     * Map a normalized intensity (0.0-1.0) to a heat-map color
     * (black → blue → cyan → green → yellow → red → white).
     */
    public static int heatMapColor(double intensity) {
        intensity = Math.max(0.0, Math.min(1.0, intensity));

        int r, g, b;
        if (intensity < 0.2) {
            float t = (float) (intensity / 0.2);
            r = 0; g = 0; b = (int) (t * 255);
        } else if (intensity < 0.4) {
            float t = (float) ((intensity - 0.2) / 0.2);
            r = 0; g = (int) (t * 255); b = 255;
        } else if (intensity < 0.6) {
            float t = (float) ((intensity - 0.4) / 0.2);
            r = 0; g = 255; b = (int) ((1 - t) * 255);
        } else if (intensity < 0.8) {
            float t = (float) ((intensity - 0.6) / 0.2);
            r = (int) (t * 255); g = 255; b = 0;
        } else {
            float t = (float) ((intensity - 0.8) / 0.2);
            r = 255; g = (int) ((1 - t) * 255); b = (int) (t * 255);
        }
        return packARGB(255, r, g, b);
    }
}
