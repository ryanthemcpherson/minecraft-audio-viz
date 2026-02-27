package com.audioviz.bitmap;

/**
 * 2D ARGB pixel buffer for bitmap-mode visualization.
 * Drawing primitives (fill, gradient, line, circle) for both
 * the server-side pattern engine and the WebSocket bitmap_frame protocol.
 *
 * Thread safety: not thread-safe. Use double-buffering externally.
 *
 * Ported from Paper plugin — removed org.bukkit.Color dependency.
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

    public int getPixel(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) return 0;
        return pixels[y * width + x];
    }

    public void setPixel(int x, int y, int argb) {
        if (x < 0 || x >= width || y < 0 || y >= height) return;
        pixels[y * width + x] = argb;
    }

    public void setPixel(int x, int y, int r, int g, int b, int a) {
        setPixel(x, y, packARGB(a, r, g, b));
    }

    public int getPixelFlat(int index) {
        if (index < 0 || index >= pixels.length) return 0;
        return pixels[index];
    }

    public int[] getRawPixels() {
        return pixels;
    }

    // ========== Drawing Primitives ==========

    public void clear(int argb) {
        java.util.Arrays.fill(pixels, argb);
    }

    public void clear() {
        clear(0x00000000);
    }

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

    public void fill(int argb) {
        java.util.Arrays.fill(pixels, argb);
    }

    public void drawHorizontalGradient(int y, int leftARGB, int rightARGB) {
        if (y < 0 || y >= height) return;
        int rowOffset = y * width;
        for (int x = 0; x < width; x++) {
            float t = (float) x / (width - 1);
            pixels[rowOffset + x] = lerpColor(leftARGB, rightARGB, t);
        }
    }

    public void drawVerticalGradient(int x, int topARGB, int bottomARGB) {
        if (x < 0 || x >= width) return;
        for (int y = 0; y < height; y++) {
            float t = (float) y / (height - 1);
            pixels[y * width + x] = lerpColor(topARGB, bottomARGB, t);
        }
    }

    public void fillColumn(int col, double normHeight, int argb) {
        if (col < 0 || col >= width) return;
        int fillRows = (int) (normHeight * height);
        fillRows = Math.max(0, Math.min(height, fillRows));
        for (int row = height - fillRows; row < height; row++) {
            pixels[row * width + col] = argb;
        }
    }

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

    public void scrollDown() {
        System.arraycopy(pixels, 0, pixels, width, (height - 1) * width);
        java.util.Arrays.fill(pixels, 0, width, 0x00000000);
    }

    public void scrollUp() {
        System.arraycopy(pixels, width, pixels, 0, (height - 1) * width);
        java.util.Arrays.fill(pixels, (height - 1) * width, pixels.length, 0x00000000);
    }

    public void scrollLeft() {
        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            System.arraycopy(pixels, rowOffset + 1, pixels, rowOffset, width - 1);
            pixels[rowOffset + width - 1] = 0x00000000;
        }
    }

    public void setRow(int y, int[] rowPixels) {
        if (y < 0 || y >= height) return;
        int count = Math.min(width, rowPixels.length);
        System.arraycopy(rowPixels, 0, pixels, y * width, count);
    }

    public void loadPixels(int[] data) {
        if (data.length != pixels.length) {
            throw new IllegalArgumentException("Data length " + data.length +
                " does not match buffer size " + pixels.length);
        }
        System.arraycopy(data, 0, pixels, 0, pixels.length);
    }

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

    public static int packARGB(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public static int rgb(int r, int g, int b) {
        return packARGB(255, r, g, b);
    }

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

    public static int fromHSB(float hue, float saturation, float brightness) {
        int rgb = java.awt.Color.HSBtoRGB(hue / 360f, saturation, brightness);
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }

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
