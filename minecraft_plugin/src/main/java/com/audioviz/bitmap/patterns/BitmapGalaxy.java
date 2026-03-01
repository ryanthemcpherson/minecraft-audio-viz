package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Swirling cosmic nebula with embedded twinkling stars.
 *
 * <p>Renders a slowly rotating galaxy with logarithmic spiral arms,
 * nebula clouds colored in deep purple and blue tones, and a field of
 * twinkling stars scattered across the background. The effect is highly
 * atmospheric and well-suited for ambient or chill music.
 *
 * <p>Audio reactivity:
 * <ul>
 *   <li>Bass controls nebula cloud brightness and density</li>
 *   <li>Beat triggers a star burst (many stars flash bright simultaneously)</li>
 *   <li>High frequencies add sparkle to individual stars</li>
 *   <li>Mid frequencies modulate the spiral arm intensity</li>
 *   <li>Overall amplitude affects the rotation speed</li>
 * </ul>
 *
 * <p>Stars are seeded at fixed pseudo-random positions and twinkle with
 * individual random phases for organic variation.
 */
public class BitmapGalaxy extends BitmapPattern {

    private static final int MAX_STARS = 60;

    /** Star positions and properties (fixed at init). */
    private double[] starX;
    private double[] starY;
    private double[] starPhase;
    private double[] starBrightness;

    /** Smooth beat pulse for star bursts. */
    private double beatPulse = 0.0;

    /** Spiral rotation angle. */
    private double spiralAngle = 0.0;

    private int lastWidth;
    private int lastHeight;
    private boolean starsInitialized = false;

    public BitmapGalaxy() {
        super("bmp_galaxy", "Galaxy",
              "Swirling cosmic nebula with twinkling stars and spiral arms");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        // Lazy-init stars
        if (!starsInitialized || lastWidth != w || lastHeight != h) {
            initStars(w, h);
        }

        double bass = audio.getBass();
        double mid = audio.getMid();
        double high = audio.getHigh();
        double amplitude = audio.getAmplitude();

        // Beat pulse
        if (audio.isBeat()) {
            beatPulse = Math.max(beatPulse, audio.getBeatIntensity());
        } else {
            beatPulse *= 0.88;
        }

        // Spiral rotation (amplitude modulates speed)
        spiralAngle += 0.01 + amplitude * 0.02;

        double centerX = w * 0.5;
        double centerY = h * 0.5;
        double maxRadius = Math.sqrt(centerX * centerX + centerY * centerY);

        // --- Render nebula background and spiral arms ---
        for (int py = 0; py < h; py++) {
            double dy = py - centerY;
            for (int px = 0; px < w; px++) {
                double dx = px - centerX;

                double radius = Math.sqrt(dx * dx + dy * dy);
                double angle = Math.atan2(dy, dx);
                double normRadius = radius / maxRadius;

                // Spiral arm structure: logarithmic spiral
                double logNormRadius = 0.4 * Math.log(Math.max(0.01, normRadius));
                double radiusFade = Math.exp(-normRadius * 1.5);
                double spiralFactor = 0;
                for (int arm = 0; arm < 2; arm++) {
                    double armAngle = angle - spiralAngle + arm * Math.PI;
                    double spiralPhase = armAngle - logNormRadius;
                    double armStrength = Math.cos(spiralPhase * 2.0) * 0.5 + 0.5;
                    armStrength *= radiusFade;
                    spiralFactor = Math.max(spiralFactor, armStrength);
                }

                // Nebula cloud: value-noise approximation using sin waves
                double cloudNoise = 0;
                cloudNoise += Math.sin(px * 0.3 + time * 0.2) * 0.3;
                cloudNoise += Math.sin(py * 0.4 - time * 0.15) * 0.3;
                cloudNoise += Math.sin((px + py) * 0.2 + time * 0.1) * 0.2;
                cloudNoise += Math.sin(radius * 0.5 - time * 0.3) * 0.2;
                cloudNoise = (cloudNoise + 1.0) * 0.5; // Normalize to 0-1

                // Combine spiral arms and cloud noise
                double nebulaIntensity = spiralFactor * 0.6 + cloudNoise * 0.3;
                nebulaIntensity *= (0.3 + bass * 0.7); // Bass drives brightness

                // Central glow
                double coreGlow = Math.exp(-normRadius * 4.0) * (0.5 + amplitude * 0.5);
                nebulaIntensity += coreGlow;

                nebulaIntensity = Math.max(0, Math.min(1, nebulaIntensity));

                // Color: deep space purples and blues with mid-frequency warmth
                double r = nebulaIntensity * (0.3 + mid * 0.4);
                double g = nebulaIntensity * (0.05 + high * 0.15);
                double b = nebulaIntensity * (0.5 + bass * 0.3);

                // Core is warmer (white/yellow)
                if (coreGlow > 0.2) {
                    double coreFactor = Math.min(1, (coreGlow - 0.2) * 2.0);
                    r += coreFactor * 0.5;
                    g += coreFactor * 0.4;
                    b += coreFactor * 0.2;
                }

                int pr = (int) (Math.min(1.0, r) * 255);
                int pg = (int) (Math.min(1.0, g) * 255);
                int pb = (int) (Math.min(1.0, b) * 255);

                buffer.setPixel(px, py, BitmapFrameBuffer.rgb(pr, pg, pb));
            }
        }

        // --- Render stars ---
        int starCount = Math.min(MAX_STARS, starX.length);
        for (int i = 0; i < starCount; i++) {
            int sx = (int) starX[i];
            int sy = (int) starY[i];
            if (sx < 0 || sx >= w || sy < 0 || sy >= h) continue;

            // Twinkle: base brightness modulated by sin wave
            double twinkle = 0.3 + 0.7 * (Math.sin(time * 2.5 + starPhase[i]) * 0.5 + 0.5);
            double brightness = starBrightness[i] * twinkle;

            // High frequencies add extra sparkle
            brightness += high * 0.3 * (Math.sin(time * 8.0 + starPhase[i] * 3.0) * 0.5 + 0.5);

            // Beat burst: all stars flash bright
            if (beatPulse > 0.1) {
                brightness += beatPulse * 0.6;
            }

            brightness = Math.min(1.0, brightness);

            // Star color: mostly white with slight blue or gold tint
            int tintSeed = (int) (starPhase[i] * 100) % 3;
            int sr, sg, sb;
            if (tintSeed == 0) {
                // Blue-white
                sr = (int) (brightness * 200);
                sg = (int) (brightness * 210);
                sb = (int) (brightness * 255);
            } else if (tintSeed == 1) {
                // Gold
                sr = (int) (brightness * 255);
                sg = (int) (brightness * 220);
                sb = (int) (brightness * 150);
            } else {
                // Pure white
                sr = (int) (brightness * 255);
                sg = (int) (brightness * 255);
                sb = (int) (brightness * 255);
            }

            // Bright center pixel
            int existing = buffer.getPixel(sx, sy);
            buffer.setPixel(sx, sy, addColors(existing, BitmapFrameBuffer.rgb(sr, sg, sb)));

            // Soft glow: dimmer surrounding pixels for brighter stars
            if (brightness > 0.5) {
                int dimColor = BitmapFrameBuffer.rgb(sr / 3, sg / 3, sb / 3);
                addGlowPixel(buffer, sx - 1, sy, dimColor);
                addGlowPixel(buffer, sx + 1, sy, dimColor);
                addGlowPixel(buffer, sx, sy - 1, dimColor);
                addGlowPixel(buffer, sx, sy + 1, dimColor);
            }
        }
    }

    /** Additively blend a glow pixel onto the buffer. */
    private void addGlowPixel(BitmapFrameBuffer buffer, int x, int y, int color) {
        int existing = buffer.getPixel(x, y);
        if (existing == 0) {
            buffer.setPixel(x, y, color);
        } else {
            buffer.setPixel(x, y, addColors(existing, color));
        }
    }

    /** Additive color blend (clamped to 255 per channel). */
    private static int addColors(int c1, int c2) {
        int r = Math.min(255, ((c1 >> 16) & 0xFF) + ((c2 >> 16) & 0xFF));
        int g = Math.min(255, ((c1 >> 8) & 0xFF) + ((c2 >> 8) & 0xFF));
        int b = Math.min(255, (c1 & 0xFF) + (c2 & 0xFF));
        return BitmapFrameBuffer.rgb(r, g, b);
    }

    private void initStars(int w, int h) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int count = Math.min(MAX_STARS, w * h / 4); // Scale with resolution
        starX = new double[count];
        starY = new double[count];
        starPhase = new double[count];
        starBrightness = new double[count];

        for (int i = 0; i < count; i++) {
            starX[i] = rng.nextDouble() * w;
            starY[i] = rng.nextDouble() * h;
            starPhase[i] = rng.nextDouble() * Math.PI * 2;
            starBrightness[i] = 0.3 + rng.nextDouble() * 0.7;
        }

        lastWidth = w;
        lastHeight = h;
        starsInitialized = true;
    }

    @Override
    public void reset() {
        beatPulse = 0.0;
        spiralAngle = 0.0;
        starsInitialized = false;
    }

    @Override
    public void onResize(int width, int height) {
        starsInitialized = false;
        lastWidth = 0;
        lastHeight = 0;
    }
}
