package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Frame feedback loop with zoom and rotation.
 *
 * <p>Reads previous frame, applies zoom toward center + rotation + hue shift,
 * then overlays new audio-reactive content. Creates infinite recursive tunnel
 * and mirror-hall effects entirely procedurally.
 */
public class BitmapFeedbackLoop extends BitmapPattern {

    private int[] previousFrame;
    private int cachedW = -1, cachedH = -1;
    private double rotation = 0;
    private double beatPulse = 0;
    private int beatCount = 0;
    private final float[] hsbWork = new float[3];

    public BitmapFeedbackLoop() {
        super("bmp_feedback", "Bitmap Feedback Loop",
              "Infinite tunnel from frame feedback with zoom and rotation");
    }

    private void ensurePreviousFrame(int w, int h) {
        if (w == cachedW && h == cachedH) return;
        cachedW = w;
        cachedH = h;
        previousFrame = new int[w * h];
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        ensurePreviousFrame(w, h);
        int[] pixels = buffer.getRawPixels();

        double bass = audio.getBass();
        double mid = audio.getMid();
        double high = audio.getHigh();
        double amplitude = audio.getAmplitude();

        if (audio.isBeat()) {
            beatPulse = 1.0;
            beatCount++;
        }
        beatPulse *= 0.88;

        // Feedback parameters
        double zoomFactor = 0.96 - bass * 0.03 - beatPulse * 0.04; // <1 = zoom in
        double rotAngle = mid * 0.04 + beatPulse * 0.02;
        rotation += rotAngle;
        double hueShiftPerFrame = high * 8 + 1.5;

        double cx = w / 2.0;
        double cy = h / 2.0;
        double cosR = Math.cos(rotation * 0.1);
        double sinR = Math.sin(rotation * 0.1);

        // Step 1: Sample previous frame with zoom + rotation → current frame
        for (int py = 0; py < h; py++) {
            double dy = (py - cy) / cy;
            for (int px = 0; px < w; px++) {
                double dx = (px - cx) / cx;

                // Apply zoom and rotation
                double rx = (dx * cosR - dy * sinR) / zoomFactor;
                double ry = (dx * sinR + dy * cosR) / zoomFactor;

                // Map back to pixel coordinates
                int sx = (int) (rx * cx + cx);
                int sy = (int) (ry * cy + cy);

                int sampled;
                if (sx >= 0 && sx < w && sy >= 0 && sy < h) {
                    sampled = previousFrame[sy * w + sx];
                } else {
                    sampled = 0xFF000000;
                }

                // Apply hue shift to sampled pixel
                sampled = shiftHue(sampled, hueShiftPerFrame);

                // Slight brightness decay to prevent blowout
                sampled = decayBrightness(sampled, 0.95);

                pixels[py * w + px] = sampled;
            }
        }

        // Step 2: Overlay new audio-reactive content
        // Pulsing central shape that changes with beats
        int shape = beatCount % 4;
        double contentRadius = 0.05 + amplitude * 0.08 + beatPulse * 0.06;

        float contentHue = (float) ((time * 40 + beatCount * 45) % 360);
        int contentColor = BitmapFrameBuffer.fromHSB(contentHue, 0.9f,
            (float) (0.6 + amplitude * 0.4));

        for (int py = 0; py < h; py++) {
            double ny = (double) py / h - 0.5;
            for (int px = 0; px < w; px++) {
                double nx = (double) px / w - 0.5;
                boolean inShape = false;

                switch (shape) {
                    case 0: // Circle
                        inShape = nx * nx + ny * ny < contentRadius * contentRadius;
                        break;
                    case 1: // Diamond
                        inShape = Math.abs(nx) + Math.abs(ny) < contentRadius;
                        break;
                    case 2: // Cross
                        inShape = (Math.abs(nx) < contentRadius * 0.3 && Math.abs(ny) < contentRadius)
                               || (Math.abs(ny) < contentRadius * 0.3 && Math.abs(nx) < contentRadius);
                        break;
                    case 3: // Ring
                        double dist = Math.sqrt(nx * nx + ny * ny);
                        inShape = Math.abs(dist - contentRadius) < contentRadius * 0.3;
                        break;
                }

                if (inShape) {
                    int idx = py * w + px;
                    // Additive blend
                    int existing = pixels[idx];
                    int er = (existing >> 16) & 0xFF, eg = (existing >> 8) & 0xFF, eb = existing & 0xFF;
                    int cr = (contentColor >> 16) & 0xFF, cg = (contentColor >> 8) & 0xFF, cb = contentColor & 0xFF;
                    pixels[idx] = BitmapFrameBuffer.packARGB(255,
                        Math.min(255, er + cr), Math.min(255, eg + cg), Math.min(255, eb + cb));
                }
            }
        }

        // Step 3: Save current frame for next iteration
        System.arraycopy(pixels, 0, previousFrame, 0, pixels.length);
    }

    private int shiftHue(int argb, double degrees) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        if (r == 0 && g == 0 && b == 0) return argb;

        java.awt.Color.RGBtoHSB(r, g, b, hsbWork);
        hsbWork[0] = (float) ((hsbWork[0] + degrees / 360.0) % 1.0);
        if (hsbWork[0] < 0) hsbWork[0] += 1.0f;
        int rgb = java.awt.Color.HSBtoRGB(hsbWork[0], hsbWork[1], hsbWork[2]);
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }

    private static int decayBrightness(int argb, double factor) {
        int r = (int) (((argb >> 16) & 0xFF) * factor);
        int g = (int) (((argb >> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return BitmapFrameBuffer.packARGB(255, r, g, b);
    }

    @Override
    public void reset() {
        beatPulse = 0;
        beatCount = 0;
        rotation = 0;
        cachedW = -1;
        cachedH = -1;
    }
}
