package com.audioviz.bitmap.media;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;

/**
 * Loads images (PNG, JPG, BMP) and renders them into bitmap frame buffers.
 *
 * <p>Supports static display and audio-reactive modulation:
 * <ul>
 *   <li><b>Pulse</b>: Image scales/brightness pulses with the beat</li>
 *   <li><b>Hue shift</b>: Rotates hue with bass energy</li>
 *   <li><b>Dissolve</b>: Pixels scatter proportional to amplitude</li>
 *   <li><b>Scan line</b>: Horizontal scan line sweeps synced to beat</li>
 * </ul>
 *
 * <p>Images are resampled to the target bitmap dimensions on load
 * using nearest-neighbor (preserving pixel-art feel) or bilinear.
 */
public class ImagePattern extends BitmapPattern {

    private static final Logger LOGGER = Logger.getLogger(ImagePattern.class.getName());

    /** The loaded image as an ARGB array at target resolution. */
    private int[] imagePixels;
    private int imageWidth;
    private int imageHeight;
    private boolean loaded = false;

    /** Modulation mode. */
    private ModulationMode mode = ModulationMode.PULSE;

    /** Animation state. */
    private double beatPulse = 0;
    private double scanPosition = 0;
    private double hueShift = 0;

    public ImagePattern() {
        super("bmp_image", "Image", "Static/modulated image display with audio reactivity");
    }

    // ========== Image Loading ==========

    /**
     * Load an image from file and resample to target dimensions.
     */
    public boolean loadFromFile(File file, int targetWidth, int targetHeight) {
        try {
            BufferedImage img = ImageIO.read(file);
            if (img == null) return false;
            return loadFromBufferedImage(img, targetWidth, targetHeight);
        } catch (IOException e) {
            LOGGER.warning("Failed to load image: " + e.getMessage());
            return false;
        }
    }

    /**
     * Load from an input stream.
     */
    public boolean loadFromStream(InputStream stream, int targetWidth, int targetHeight) {
        try {
            BufferedImage img = ImageIO.read(stream);
            if (img == null) return false;
            return loadFromBufferedImage(img, targetWidth, targetHeight);
        } catch (IOException e) {
            LOGGER.warning("Failed to load image: " + e.getMessage());
            return false;
        }
    }

    /**
     * Load from a raw ARGB pixel array (e.g., received over WebSocket).
     */
    public void loadFromPixels(int[] pixels, int width, int height) {
        this.imagePixels = pixels.clone();
        this.imageWidth = width;
        this.imageHeight = height;
        this.loaded = true;
    }

    private boolean loadFromBufferedImage(BufferedImage source, int targetWidth, int targetHeight) {
        // Resample to target dimensions
        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        g.dispose();

        // Extract ARGB pixels
        this.imageWidth = targetWidth;
        this.imageHeight = targetHeight;
        this.imagePixels = resized.getRGB(0, 0, targetWidth, targetHeight, null, 0, targetWidth);
        this.loaded = true;
        return true;
    }

    // ========== Configuration ==========

    public void setMode(ModulationMode mode) { this.mode = mode; }
    public ModulationMode getMode() { return mode; }
    public boolean isLoaded() { return loaded; }

    // ========== Rendering ==========

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        if (!loaded || imagePixels == null) {
            buffer.fill(0xFF111111);
            return;
        }

        // Beat tracking
        if (audio != null && audio.isBeat()) {
            beatPulse = 1.0;
        }

        double amplitude = audio != null ? audio.getAmplitude() : 0;

        switch (mode) {
            case STATIC -> renderStatic(buffer);
            case PULSE -> renderPulse(buffer, amplitude);
            case HUE_SHIFT -> renderHueShift(buffer, amplitude, time);
            case DISSOLVE -> renderDissolve(buffer, amplitude, time);
            case SCAN_LINE -> renderScanLine(buffer, amplitude, time);
        }

        beatPulse *= 0.85;
    }

    private void renderStatic(BitmapFrameBuffer buffer) {
        int[] out = buffer.getRawPixels();
        int len = Math.min(out.length, imagePixels.length);
        System.arraycopy(imagePixels, 0, out, 0, len);
    }

    private void renderPulse(BitmapFrameBuffer buffer, double amplitude) {
        int[] out = buffer.getRawPixels();
        int len = Math.min(out.length, imagePixels.length);

        // Brightness modulation: base + beat pulse + amplitude
        double bright = 0.7 + beatPulse * 0.3 + amplitude * 0.2;
        bright = Math.min(1.5, bright);

        for (int i = 0; i < len; i++) {
            int c = imagePixels[i];
            int a = (c >> 24) & 0xFF;
            int r = Math.min(255, (int) (((c >> 16) & 0xFF) * bright));
            int g = Math.min(255, (int) (((c >> 8) & 0xFF) * bright));
            int b = Math.min(255, (int) ((c & 0xFF) * bright));
            out[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    private void renderHueShift(BitmapFrameBuffer buffer, double amplitude, double time) {
        int[] out = buffer.getRawPixels();
        int len = Math.min(out.length, imagePixels.length);

        // Hue rotates with bass energy
        hueShift += amplitude * 2.0;
        double shift = hueShift % 360;

        for (int i = 0; i < len; i++) {
            out[i] = shiftHue(imagePixels[i], shift);
        }
    }

    private void renderDissolve(BitmapFrameBuffer buffer, double amplitude, double time) {
        int[] out = buffer.getRawPixels();
        buffer.fill(0xFF000000);
        int len = Math.min(out.length, imagePixels.length);

        // Pixels appear/disappear based on amplitude threshold
        double threshold = 1.0 - amplitude;

        for (int i = 0; i < len; i++) {
            double hash = spatialHash(i, 12345);
            if (hash > threshold) {
                out[i] = imagePixels[i];
            }
        }
    }

    private void renderScanLine(BitmapFrameBuffer buffer, double amplitude, double time) {
        int[] out = buffer.getRawPixels();
        int len = Math.min(out.length, imagePixels.length);

        // Copy base image dimmed
        for (int i = 0; i < len; i++) {
            int c = imagePixels[i];
            int r = ((c >> 16) & 0xFF) / 3;
            int g = ((c >> 8) & 0xFF) / 3;
            int b = (c & 0xFF) / 3;
            out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        // Scanning line position
        scanPosition += 0.5 + amplitude;
        if (scanPosition >= imageHeight) scanPosition = 0;
        int scanY = (int) scanPosition;

        // Brighten rows near the scan line
        for (int dy = -2; dy <= 2; dy++) {
            int y = scanY + dy;
            if (y < 0 || y >= imageHeight) continue;
            double intensity = 1.0 - Math.abs(dy) / 3.0;
            for (int x = 0; x < imageWidth; x++) {
                int idx = y * imageWidth + x;
                if (idx >= len) break;
                out[idx] = BitmapFrameBuffer.lerpColor(out[idx], imagePixels[idx], (float) intensity);
            }
        }
    }

    // ========== Color Utilities ==========

    private static int shiftHue(int argb, double degrees) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;

        // RGB to HSV
        float[] hsv = rgbToHsv(r, g, b);
        hsv[0] = (float) ((hsv[0] + degrees) % 360);
        if (hsv[0] < 0) hsv[0] += 360;

        // HSV back to RGB
        int[] rgb = hsvToRgb(hsv[0], hsv[1], hsv[2]);
        return (a << 24) | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
    }

    private static float[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;

        float h = 0;
        if (delta > 0) {
            if (max == rf) h = 60 * (((gf - bf) / delta) % 6);
            else if (max == gf) h = 60 * ((bf - rf) / delta + 2);
            else h = 60 * ((rf - gf) / delta + 4);
        }
        if (h < 0) h += 360;

        float s = (max > 0) ? delta / max : 0;
        return new float[]{h, s, max};
    }

    private static int[] hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float x = c * (1 - Math.abs((h / 60) % 2 - 1));
        float m = v - c;
        float r, g, b;

        if (h < 60)       { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else               { r = c; g = 0; b = x; }

        return new int[]{
            (int) ((r + m) * 255),
            (int) ((g + m) * 255),
            (int) ((b + m) * 255)
        };
    }

    private static double spatialHash(int index, int seed) {
        int n = index * 374761393 + seed;
        n = (n ^ (n >> 13)) * 1274126177;
        n = n ^ (n >> 16);
        return (n & 0x7FFFFFFF) / (double) 0x7FFFFFFF;
    }

    @Override
    public void reset() {
        beatPulse = 0;
        scanPosition = 0;
        hueShift = 0;
    }

    // ========== Modulation Modes ==========

    public enum ModulationMode {
        /** No modulation — display image as-is. */
        STATIC,
        /** Brightness pulses with beat/amplitude. */
        PULSE,
        /** Hue rotates proportional to bass energy. */
        HUE_SHIFT,
        /** Pixels scatter/appear based on amplitude. */
        DISSOLVE,
        /** Horizontal scan line sweeps through the image. */
        SCAN_LINE
    }
}
