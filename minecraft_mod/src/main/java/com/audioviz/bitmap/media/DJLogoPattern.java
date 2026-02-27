package com.audioviz.bitmap.media;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.bitmap.effects.ColorPalette;
import com.audioviz.patterns.AudioState;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.logging.Logger;

/**
 * Audio-reactive DJ logo/branding pattern.
 *
 * <p>Loads a logo image (file or raw pixels), detects foreground vs background
 * via brightness threshold, and renders four visualization modes:
 * <ul>
 *   <li><b>AURA_GLOW</b>: Logo on black, edge pixels radiate colored glow</li>
 *   <li><b>SPECTRUM_FILL</b>: Logo shape masks a frequency spectrum fill</li>
 *   <li><b>SILHOUETTE_PULSE</b>: Animated plasma background with solid pulsing logo</li>
 *   <li><b>RING_BURST</b>: Beat-triggered rings expand from logo centroid</li>
 * </ul>
 */
public class DJLogoPattern extends BitmapPattern {

    private static final Logger LOGGER = Logger.getLogger(DJLogoPattern.class.getName());

    /** Visualization modes. */
    public enum LogoMode {
        AURA_GLOW,
        SPECTRUM_FILL,
        SILHOUETTE_PULSE,
        RING_BURST
    }

    // Image data
    private int[] imagePixels;
    private int imageWidth;
    private int imageHeight;
    private boolean loaded = false;
    private BufferedImage sourceImage; // Cache original for resize

    // Pre-computed masks (sized to buffer, not source image)
    private boolean[] foregroundMask;
    private boolean[] edgeMask;
    private int[] distanceField;
    private int centroidX;
    private int centroidY;
    private int maskWidth;
    private int maskHeight;

    // Content bounds within letterboxed image (only these pixels can be foreground)
    private int contentX, contentY, contentW, contentH;

    // Configuration
    private LogoMode mode = LogoMode.AURA_GLOW;
    private int brightnessThreshold = 128;
    private ColorPalette palette = ColorPalette.NEON;

    // Animation state
    private double beatPulse = 0;
    private double beatFlash = 0;

    // Ring burst state (pool of 4)
    private static final int MAX_RINGS = 4;
    private final double[] ringRadius = new double[MAX_RINGS];
    private final double[] ringLife = new double[MAX_RINGS];  // 1.0 = just spawned, 0.0 = dead
    private int nextRing = 0;

    public DJLogoPattern() {
        super("bmp_dj_logo", "DJ Logo", "Audio-reactive DJ logo with glow, spectrum fill, silhouette, and ring burst modes");
    }

    // ========== Image Loading ==========

    public boolean loadFromFile(File file, int targetWidth, int targetHeight) {
        try {
            BufferedImage img = ImageIO.read(file);
            if (img == null) return false;
            this.sourceImage = img;
            return loadFromBufferedImage(img, targetWidth, targetHeight);
        } catch (IOException e) {
            LOGGER.warning("Failed to load DJ logo: " + e.getMessage());
            return false;
        }
    }

    public void loadFromPixels(int[] pixels, int width, int height) {
        this.imagePixels = pixels.clone();
        this.imageWidth = width;
        this.imageHeight = height;
        this.loaded = true;
        // Masks will be computed on first render when we know buffer dimensions
        this.foregroundMask = null;
    }

    private boolean loadFromBufferedImage(BufferedImage source, int targetWidth, int targetHeight) {
        // Step 1: Auto-crop whitespace
        BufferedImage cropped = autoCrop(source);

        // Step 2: Scale to fit within target while preserving aspect ratio
        double scaleX = (double) targetWidth / cropped.getWidth();
        double scaleY = (double) targetHeight / cropped.getHeight();
        double scale = Math.min(scaleX, scaleY);
        int fitW = Math.max(1, (int) (cropped.getWidth() * scale));
        int fitH = Math.max(1, (int) (cropped.getHeight() * scale));

        // Step 3: Letterbox — center on black canvas at target size
        BufferedImage result = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(0, 0, targetWidth, targetHeight);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        int offsetX = (targetWidth - fitW) / 2;
        int offsetY = (targetHeight - fitH) / 2;
        g.drawImage(cropped, offsetX, offsetY, fitW, fitH, null);
        g.dispose();

        // Store content bounds so letterbox margins aren't treated as foreground
        this.contentX = offsetX;
        this.contentY = offsetY;
        this.contentW = fitW;
        this.contentH = fitH;

        this.imageWidth = targetWidth;
        this.imageHeight = targetHeight;
        this.imagePixels = result.getRGB(0, 0, targetWidth, targetHeight, null, 0, targetWidth);
        this.loaded = true;
        this.foregroundMask = null;
        return true;
    }

    /**
     * Auto-crop whitespace from a logo image using the brightness threshold.
     * Scans for bounding box of dark pixels, adds 2px padding, returns cropped image.
     */
    private BufferedImage autoCrop(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);

        int minX = w, minY = h, maxX = 0, maxY = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = pixels[y * w + x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                int brightness = (r * 299 + g * 587 + b * 114) / 1000;
                if (brightness < brightnessThreshold) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }

        // No content found — return original
        if (maxX < minX) return img;

        // Add padding (2px in source space, clamped to image bounds)
        int pad = Math.max(2, Math.min(w, h) / 50); // ~2% of smaller dimension
        minX = Math.max(0, minX - pad);
        minY = Math.max(0, minY - pad);
        maxX = Math.min(w - 1, maxX + pad);
        maxY = Math.min(h - 1, maxY + pad);

        int cropW = maxX - minX + 1;
        int cropH = maxY - minY + 1;
        return img.getSubimage(minX, minY, cropW, cropH);
    }

    // ========== Mask Computation ==========

    /**
     * Compute foreground/edge masks and distance field for the current buffer dimensions.
     * Resamples the source image to buffer size if needed.
     */
    private void computeMasks(int bufWidth, int bufHeight) {
        if (!loaded || imagePixels == null) return;

        this.maskWidth = bufWidth;
        this.maskHeight = bufHeight;
        int pixelCount = bufWidth * bufHeight;

        // Resample source image to buffer dimensions if they differ
        int[] resampledPixels;
        if (imageWidth == bufWidth && imageHeight == bufHeight) {
            resampledPixels = imagePixels;
        } else {
            resampledPixels = resampleNearest(imagePixels, imageWidth, imageHeight, bufWidth, bufHeight);
        }

        // Foreground mask: dark pixels = logo
        foregroundMask = new boolean[pixelCount];
        long fgSumX = 0, fgSumY = 0;
        int fgCount = 0;

        // Scale content bounds to buffer dimensions
        int cxStart = contentW > 0 ? contentX * bufWidth / imageWidth : 0;
        int cyStart = contentH > 0 ? contentY * bufHeight / imageHeight : 0;
        int cxEnd = contentW > 0 ? (contentX + contentW) * bufWidth / imageWidth : bufWidth;
        int cyEnd = contentH > 0 ? (contentY + contentH) * bufHeight / imageHeight : bufHeight;

        for (int y = 0; y < bufHeight; y++) {
            for (int x = 0; x < bufWidth; x++) {
                int idx = y * bufWidth + x;

                // Skip letterbox margin pixels — only content area can be foreground
                if (x < cxStart || x >= cxEnd || y < cyStart || y >= cyEnd) continue;

                int c = resampledPixels[idx];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                int brightness = (r * 299 + g * 587 + b * 114) / 1000;

                if (brightness < brightnessThreshold) {
                    foregroundMask[idx] = true;
                    fgSumX += x;
                    fgSumY += y;
                    fgCount++;
                }
            }
        }

        // Centroid
        if (fgCount > 0) {
            centroidX = (int) (fgSumX / fgCount);
            centroidY = (int) (fgSumY / fgCount);
        } else {
            centroidX = bufWidth / 2;
            centroidY = bufHeight / 2;
        }

        // Edge mask: foreground pixels with at least one background neighbor (4-connected)
        edgeMask = new boolean[pixelCount];
        for (int y = 0; y < bufHeight; y++) {
            for (int x = 0; x < bufWidth; x++) {
                int idx = y * bufWidth + x;
                if (!foregroundMask[idx]) continue;
                if ((x > 0 && !foregroundMask[idx - 1])
                    || (x < bufWidth - 1 && !foregroundMask[idx + 1])
                    || (y > 0 && !foregroundMask[idx - bufWidth])
                    || (y < bufHeight - 1 && !foregroundMask[idx + bufWidth])) {
                    edgeMask[idx] = true;
                }
            }
        }

        // Distance field via BFS from edge pixels (scaled to canvas so effects fill display)
        distanceField = new int[pixelCount];
        java.util.Arrays.fill(distanceField, Integer.MAX_VALUE);
        Queue<Integer> queue = new ArrayDeque<>();

        for (int i = 0; i < pixelCount; i++) {
            if (edgeMask[i]) {
                distanceField[i] = 0;
                queue.add(i);
            }
        }

        int maxDist = Math.max(bufWidth, bufHeight);
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            int dist = distanceField[idx];
            if (dist >= maxDist) continue;
            int x = idx % bufWidth;
            int y = idx / bufWidth;

            int[][] neighbors = {{x - 1, y}, {x + 1, y}, {x, y - 1}, {x, y + 1}};
            for (int[] n : neighbors) {
                if (n[0] < 0 || n[0] >= bufWidth || n[1] < 0 || n[1] >= bufHeight) continue;
                int ni = n[1] * bufWidth + n[0];
                if (dist + 1 < distanceField[ni]) {
                    distanceField[ni] = dist + 1;
                    queue.add(ni);
                }
            }
        }
    }

    private static int[] resampleNearest(int[] src, int srcW, int srcH, int dstW, int dstH) {
        int[] dst = new int[dstW * dstH];
        for (int y = 0; y < dstH; y++) {
            int sy = y * srcH / dstH;
            for (int x = 0; x < dstW; x++) {
                int sx = x * srcW / dstW;
                dst[y * dstW + x] = src[sy * srcW + sx];
            }
        }
        return dst;
    }

    // ========== Configuration ==========

    public void setMode(LogoMode mode) { this.mode = mode; }
    public LogoMode getMode() { return mode; }
    public boolean isLoaded() { return loaded; }

    public void setThreshold(int threshold) {
        this.brightnessThreshold = Math.max(1, Math.min(255, threshold));
        this.foregroundMask = null; // Force recompute
    }

    public void setPalette(ColorPalette palette) {
        if (palette != null) this.palette = palette;
    }

    // ========== Rendering ==========

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        if (!loaded || imagePixels == null) {
            buffer.fill(0xFF111111);
            return;
        }

        // Lazy-compute masks on first render or after resize/threshold change
        if (foregroundMask == null || maskWidth != buffer.getWidth() || maskHeight != buffer.getHeight()) {
            computeMasks(buffer.getWidth(), buffer.getHeight());
        }

        // Beat tracking
        if (audio != null && audio.isBeat()) {
            beatPulse = 1.0;
            beatFlash = 1.0;
            // Spawn ring for RING_BURST mode
            if (mode == LogoMode.RING_BURST) {
                ringRadius[nextRing] = 0;
                ringLife[nextRing] = 1.0;
                nextRing = (nextRing + 1) % MAX_RINGS;
            }
        }

        switch (mode) {
            case AURA_GLOW -> renderAuraGlow(buffer, audio, time);
            case SPECTRUM_FILL -> renderSpectrumFill(buffer, audio, time);
            case SILHOUETTE_PULSE -> renderSilhouettePulse(buffer, audio, time);
            case RING_BURST -> renderRingBurst(buffer, audio, time);
        }

        // Decay animation state
        beatPulse *= 0.85;
        beatFlash *= 0.90;
    }

    // ========== Helper: stamp logo on top of background ==========

    /** Stamp the logo foreground onto the buffer. Logo = palette color, pulsing with beat. */
    private void stampLogo(int[] out, int total, double amp) {
        double bright = 0.7 + amp * 0.2 + beatPulse * 0.1;
        int logoColor = scaleColor(palette.mapSmooth(0.9), bright);
        for (int i = 0; i < total; i++) {
            if (foregroundMask[i]) {
                out[i] = logoColor;
            }
        }
    }

    // ---------- Mode 1: AURA_GLOW ----------
    // Full-canvas distance-field glow, logo stamped on top

    private void renderAuraGlow(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        int[] out = buffer.getRawPixels();
        double bass = audio != null ? audio.getBass() : 0;
        double amp = audio != null ? audio.getAmplitude() : 0;

        // Glow fills entire canvas — radius scales with bass
        double maxRadius = Math.max(w, h);
        double glowRadius = maxRadius * 0.3 + bass * maxRadius * 0.7;

        // Background: distance-field glow across full canvas
        for (int i = 0; i < w * h; i++) {
            int dist = distanceField[i];
            if (dist < Integer.MAX_VALUE && dist <= glowRadius) {
                double intensity = 1.0 - dist / glowRadius;
                intensity *= (0.5 + amp * 0.5);
                intensity = Math.min(1.0, intensity + beatFlash * 0.4 * Math.max(0, 1.0 - dist / (glowRadius * 0.5)));
                out[i] = palette.mapSmooth(intensity);
            } else {
                // Ambient far-field: subtle bass-reactive color instead of pure black
                double ambient = bass * 0.08;
                out[i] = ambient > 0.01 ? palette.mapSmooth(ambient) : 0xFF000000;
            }
        }

        // Logo on top
        stampLogo(out, w * h, amp);
    }

    // ---------- Mode 2: SPECTRUM_FILL ----------
    // Full-width EQ bars across entire canvas, logo punches through as bright overlay

    private void renderSpectrumFill(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        int[] out = buffer.getRawPixels();
        double[] bands = audio != null ? audio.getBands() : new double[5];
        double amp = audio != null ? audio.getAmplitude() : 0;

        // Background: full-width frequency bars
        for (int y = 0; y < h; y++) {
            double rowNorm = 1.0 - (double) y / h; // 0 at bottom, 1 at top
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;

                // Interpolate between adjacent bands for smooth gradient
                double bandPos = (double) x / w * 4.0; // 0-4 across width
                int bandLo = Math.min(4, (int) bandPos);
                int bandHi = Math.min(4, bandLo + 1);
                double frac = bandPos - bandLo;
                double bandLevel = bands[bandLo] * (1.0 - frac) + bands[bandHi] * frac;

                if (rowNorm <= bandLevel) {
                    double colorPos = (double) x / w;
                    double bright = 0.5 + beatPulse * 0.3;
                    out[idx] = scaleColor(palette.mapSmooth(colorPos), bright);
                } else {
                    // Dim above fill level — subtle grid lines
                    out[idx] = 0xFF080808;
                }
            }
        }

        // Logo punches through as bright overlay
        double logoBright = 0.85 + beatPulse * 0.15;
        for (int i = 0; i < w * h; i++) {
            if (foregroundMask[i]) {
                // Brighten the underlying EQ color where logo is
                int bg = out[i];
                int boosted = scaleColor(bg, 2.5);
                out[i] = BitmapFrameBuffer.lerpColor(boosted, 0xFFFFFFFF, (float) (0.4 + beatPulse * 0.3));
            }
        }
    }

    // ---------- Mode 3: SILHOUETTE_PULSE ----------
    // Plasma fills entire canvas, logo is solid shape on top

    private void renderSilhouettePulse(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        int[] out = buffer.getRawPixels();
        double bass = audio != null ? audio.getBass() : 0;
        double amp = audio != null ? audio.getAmplitude() : 0;

        // Background: animated plasma across entire canvas
        for (int y = 0; y < h; y++) {
            double ny = (double) y / h;
            for (int x = 0; x < w; x++) {
                double nx = (double) x / w;
                double plasma = 0.5
                    + 0.25 * Math.sin(nx * 6.0 + time * 1.5 + bass * 3.0)
                    + 0.25 * Math.sin(ny * 4.0 + time * 0.8 + bass * 2.0);
                plasma = Math.max(0, Math.min(1, plasma));
                out[y * w + x] = palette.mapSmooth(plasma);
            }
        }

        // Logo on top
        stampLogo(out, w * h, amp);
    }

    // ---------- Mode 4: RING_BURST ----------
    // Bass gradient background, expanding rings, logo on top

    private void renderRingBurst(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        int[] out = buffer.getRawPixels();
        double amp = audio != null ? audio.getAmplitude() : 0;
        double bass = audio != null ? audio.getBass() : 0;

        // Background: radial gradient from centroid, pulsing with bass
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx = x - centroidX;
                int dy = y - centroidY;
                double dist = Math.sqrt(dx * dx + dy * dy);
                double maxDist = Math.sqrt(w * w + h * h) * 0.5;
                double intensity = (1.0 - dist / maxDist) * bass * 0.25;
                out[y * w + x] = intensity > 0.01 ? palette.mapSmooth(Math.min(1, intensity)) : 0xFF000000;
            }
        }

        // Draw rings
        for (int r = 0; r < MAX_RINGS; r++) {
            if (ringLife[r] <= 0) continue;

            ringRadius[r] += 0.8;
            ringLife[r] -= 0.03;
            if (ringLife[r] <= 0) continue;

            int radius = (int) ringRadius[r];
            double life = ringLife[r];
            int thickness = Math.max(1, (int) (2 * life));
            int ringColor = scaleColor(palette.mapSmooth(life), life);
            drawRingPixels(out, w, h, centroidX, centroidY, radius, thickness, ringColor);
        }

        // Logo on top
        stampLogo(out, w * h, amp);
    }

    /**
     * Draw a ring directly into a pixel array (avoids buffer.drawRing which
     * overwrites logo pixels — we need rings behind logo).
     */
    private void drawRingPixels(int[] pixels, int w, int h, int cx, int cy,
                                int radius, int thickness, int color) {
        int rOuter = radius + thickness / 2;
        int rInner = Math.max(0, radius - thickness / 2);
        int rOuterSq = rOuter * rOuter;
        int rInnerSq = rInner * rInner;

        int yStart = Math.max(0, cy - rOuter);
        int yEnd = Math.min(h - 1, cy + rOuter);
        int xStart = Math.max(0, cx - rOuter);
        int xEnd = Math.min(w - 1, cx + rOuter);

        for (int y = yStart; y <= yEnd; y++) {
            for (int x = xStart; x <= xEnd; x++) {
                int dx = x - cx;
                int dy = y - cy;
                int distSq = dx * dx + dy * dy;
                if (distSq >= rInnerSq && distSq <= rOuterSq) {
                    int idx = y * w + x;
                    // Blend with existing pixel (additive-ish)
                    pixels[idx] = BitmapFrameBuffer.lerpColor(pixels[idx], color, 0.7f);
                }
            }
        }
    }

    // ========== Color Utility ==========

    private static int scaleColor(int argb, double scale) {
        int a = (argb >> 24) & 0xFF;
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * scale));
        int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * scale));
        int b = Math.min(255, (int) ((argb & 0xFF) * scale));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ========== Lifecycle ==========

    @Override
    public void reset() {
        beatPulse = 0;
        beatFlash = 0;
        for (int i = 0; i < MAX_RINGS; i++) {
            ringRadius[i] = 0;
            ringLife[i] = 0;
        }
        nextRing = 0;
    }

    @Override
    public void onResize(int width, int height) {
        // Re-resample from cached source if available
        if (sourceImage != null) {
            loadFromBufferedImage(sourceImage, width, height);
        }
        foregroundMask = null; // Force recompute on next render
    }
}
