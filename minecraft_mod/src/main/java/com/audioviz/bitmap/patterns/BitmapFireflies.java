package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Soft glowing bokeh orbs drifting and pulsing like fireflies.
 *
 * <p>Renders a field of gentle, floating light particles with soft glow
 * halos. Each firefly drifts slowly with slight random motion, pulsing
 * its brightness with an individual sinusoidal phase. The effect creates
 * a calming, organic atmosphere ideal for ambient and chill music.
 *
 * <p>Audio reactivity:
 * <ul>
 *   <li>Mid frequencies control drift speed — faster movement with more energy</li>
 *   <li>Bass controls overall glow intensity</li>
 *   <li>Beat triggers a synchronized flash where all fireflies pulse bright</li>
 *   <li>New fireflies spawn on beat, old ones gradually fade out</li>
 *   <li>High frequencies shift hue from warm amber toward cool blue</li>
 * </ul>
 *
 * <p>Each firefly is drawn as a bright center pixel surrounded by a
 * soft glow kernel (3x3 or 5x5 depending on brightness) for a bokeh look.
 */
public class BitmapFireflies extends BitmapPattern {

    private static final int MAX_FIREFLIES = 40;

    // Firefly state arrays
    private final double[] flyX = new double[MAX_FIREFLIES];
    private final double[] flyY = new double[MAX_FIREFLIES];
    private final double[] flyDx = new double[MAX_FIREFLIES];
    private final double[] flyDy = new double[MAX_FIREFLIES];
    private final double[] flyBrightness = new double[MAX_FIREFLIES];
    private final double[] flyGlowPhase = new double[MAX_FIREFLIES];
    private final float[] flyHue = new float[MAX_FIREFLIES];
    private final double[] flyLife = new double[MAX_FIREFLIES]; // 1.0 = fresh, 0.0 = dead

    private int activeCount = 0;
    private int lastWidth;
    private int lastHeight;

    /** Smooth beat pulse for synchronized flash. */
    private double beatPulse = 0.0;

    public BitmapFireflies() {
        super("bmp_fireflies", "Fireflies",
              "Soft glowing bokeh orbs drifting and pulsing with the music");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        lastWidth = w;
        lastHeight = h;

        ThreadLocalRandom rng = ThreadLocalRandom.current();

        double bass = audio.getBass();
        double mid = audio.getMid();
        double high = audio.getHigh();
        double amplitude = audio.getAmplitude();

        // Beat pulse
        if (audio.isBeat()) {
            beatPulse = Math.max(beatPulse, audio.getBeatIntensity());
        } else {
            beatPulse *= 0.85;
        }

        // --- Recount active fireflies before spawning ---
        activeCount = 0;
        for (int i = 0; i < MAX_FIREFLIES; i++) {
            if (flyLife[i] > 0.01) activeCount++;
        }

        // --- Spawn new fireflies on beat ---
        if (audio.isBeat()) {
            int spawnCount = 2 + (int) (audio.getBeatIntensity() * 3);
            for (int s = 0; s < spawnCount && activeCount < MAX_FIREFLIES; s++) {
                int slot = findDeadSlot();
                if (slot >= 0) {
                    flyX[slot] = rng.nextDouble() * w;
                    flyY[slot] = rng.nextDouble() * h;
                    flyDx[slot] = (rng.nextDouble() - 0.5) * 0.8;
                    flyDy[slot] = (rng.nextDouble() - 0.5) * 0.8;
                    flyBrightness[slot] = 0.5 + rng.nextDouble() * 0.5;
                    flyGlowPhase[slot] = rng.nextDouble() * Math.PI * 2;
                    flyLife[slot] = 1.0;

                    // Hue: warm amber base, high frequencies shift toward cool blue
                    float baseHue = 30f + rng.nextFloat() * 20f; // 30-50 (amber/gold)
                    flyHue[slot] = baseHue + (float) (high * 180); // Shift toward blue with highs

                    activeCount++;
                }
            }
        }

        // Also spawn a trickle even without beats to maintain minimum population
        if (activeCount < 8 && rng.nextDouble() < 0.15) {
            int slot = findDeadSlot();
            if (slot >= 0) {
                flyX[slot] = rng.nextDouble() * w;
                flyY[slot] = rng.nextDouble() * h;
                flyDx[slot] = (rng.nextDouble() - 0.5) * 0.4;
                flyDy[slot] = (rng.nextDouble() - 0.5) * 0.4;
                flyBrightness[slot] = 0.3 + rng.nextDouble() * 0.4;
                flyGlowPhase[slot] = rng.nextDouble() * Math.PI * 2;
                flyLife[slot] = 1.0;
                flyHue[slot] = 35f + rng.nextFloat() * 15f;
                activeCount++;
            }
        }

        // --- Clear to transparent background ---
        buffer.clear();

        // --- Update and render each firefly ---
        double driftSpeed = 0.3 + mid * 1.5;
        double glowIntensity = 0.4 + bass * 0.6;

        activeCount = 0; // Recount
        for (int i = 0; i < MAX_FIREFLIES; i++) {
            if (flyLife[i] <= 0.01) continue;
            activeCount++;

            // Update position with drift
            flyX[i] += flyDx[i] * driftSpeed;
            flyY[i] += flyDy[i] * driftSpeed;

            // Gentle random walk
            flyDx[i] += (rng.nextDouble() - 0.5) * 0.1;
            flyDy[i] += (rng.nextDouble() - 0.5) * 0.1;

            // Dampen velocity
            flyDx[i] *= 0.97;
            flyDy[i] *= 0.97;

            // Wrap around edges
            if (flyX[i] < -2) flyX[i] = w + 1;
            if (flyX[i] > w + 2) flyX[i] = -1;
            if (flyY[i] < -2) flyY[i] = h + 1;
            if (flyY[i] > h + 2) flyY[i] = -1;

            // Life decay
            flyLife[i] -= 0.003 + (1.0 - amplitude) * 0.002; // Fade faster when quiet
            if (flyLife[i] <= 0) {
                flyLife[i] = 0;
                continue;
            }

            // Pulsing brightness
            double pulse = Math.sin(time * 2.0 + flyGlowPhase[i]) * 0.5 + 0.5;
            double brightness = flyBrightness[i] * pulse * glowIntensity * flyLife[i];

            // Beat flash: all fireflies pulse bright
            brightness += beatPulse * 0.5;
            brightness = Math.min(1.0, brightness);

            if (brightness < 0.05) continue;

            // Calculate color
            float hue = flyHue[i];
            float sat = 0.6f + (float) (flyLife[i] * 0.3);
            float bri = (float) brightness;

            int coreColor = BitmapFrameBuffer.fromHSB(hue, sat, bri);

            int cx = (int) flyX[i];
            int cy = (int) flyY[i];

            // Draw glow kernel
            if (brightness > 0.4) {
                // 5x5 soft glow for bright fireflies
                drawGlow5x5(buffer, cx, cy, hue, sat, (float) brightness);
            } else {
                // 3x3 glow for dimmer ones
                drawGlow3x3(buffer, cx, cy, hue, sat, (float) brightness);
            }

            // Bright center pixel
            buffer.setPixel(cx, cy, coreColor);
        }
    }

    /** Draw a 3x3 soft glow centered at (cx, cy). */
    private void drawGlow3x3(BitmapFrameBuffer buffer, int cx, int cy,
                              float hue, float sat, float brightness) {
        float dimBri = brightness * 0.3f;
        int dimColor = BitmapFrameBuffer.fromHSB(hue, sat * 0.8f, dimBri);

        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                addPixel(buffer, cx + dx, cy + dy, dimColor);
            }
        }
    }

    /** Draw a 5x5 soft glow centered at (cx, cy) with falloff. */
    private void drawGlow5x5(BitmapFrameBuffer buffer, int cx, int cy,
                              float hue, float sat, float brightness) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                if (dx == 0 && dy == 0) continue;
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist > 2.5) continue;

                float falloff = (float) (1.0 - dist / 2.5) * brightness * 0.35f;
                if (falloff < 0.02f) continue;

                int glowColor = BitmapFrameBuffer.fromHSB(hue, sat * 0.7f, falloff);
                addPixel(buffer, cx + dx, cy + dy, glowColor);
            }
        }
    }

    /** Additive pixel blend. */
    private void addPixel(BitmapFrameBuffer buffer, int x, int y, int color) {
        int existing = buffer.getPixel(x, y);
        int r = Math.min(255, ((existing >> 16) & 0xFF) + ((color >> 16) & 0xFF));
        int g = Math.min(255, ((existing >> 8) & 0xFF) + ((color >> 8) & 0xFF));
        int b = Math.min(255, (existing & 0xFF) + (color & 0xFF));
        buffer.setPixel(x, y, BitmapFrameBuffer.rgb(r, g, b));
    }

    /** Find the first dead (life <= 0) firefly slot, or -1 if all active. */
    private int findDeadSlot() {
        for (int i = 0; i < MAX_FIREFLIES; i++) {
            if (flyLife[i] <= 0.01) return i;
        }
        return -1;
    }

    @Override
    public void reset() {
        for (int i = 0; i < MAX_FIREFLIES; i++) {
            flyLife[i] = 0;
        }
        activeCount = 0;
        beatPulse = 0.0;
    }
}
