package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Sacred geometry spiral pattern with golden ratio arms.
 *
 * <p>Five interlocking golden spiral arms, each mapped to a frequency
 * band. Background concentric rings breathe with bass. Rotation tracks
 * audio energy. Alex Grey / DMT visual territory.
 */
public class BitmapSacredSpiral extends BitmapPattern {

    private float[] distTable;
    private float[] angleTable;
    private int cachedW = -1, cachedH = -1;
    private double rotation = 0;
    private double beatPulse = 0;
    private double ringPhase = 0;

    private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0)); // ~137.5 degrees
    private static final double LOG_GOLDEN = Math.log(1.618033988749895);
    private static final int NUM_ARMS = 5;

    public BitmapSacredSpiral() {
        super("bmp_sacred_spiral", "Bitmap Sacred Spiral",
              "Golden ratio spiral arms with breathing concentric rings");
    }

    private void ensureTables(int w, int h) {
        if (w == cachedW && h == cachedH) return;
        cachedW = w;
        cachedH = h;
        int size = w * h;
        distTable = new float[size];
        angleTable = new float[size];
        double cx = w / 2.0;
        double cy = h / 2.0;
        double scale = Math.max(cx, cy);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = (x - cx) / scale;
                double dy = (y - cy) / scale;
                int idx = y * w + x;
                distTable[idx] = (float) Math.sqrt(dx * dx + dy * dy);
                angleTable[idx] = (float) Math.atan2(dy, dx);
            }
        }
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        ensureTables(w, h);
        int[] pixels = buffer.getRawPixels();

        double bass = audio.getBass();
        double mid = audio.getMid();
        double high = audio.getHigh();
        double amplitude = audio.getAmplitude();
        double[] bands = audio.getBands();

        if (audio.isBeat()) {
            beatPulse = 1.0;
        }
        beatPulse *= 0.88;

        rotation += 0.015 + amplitude * 0.03 + beatPulse * 0.06;
        ringPhase += 0.02 + bass * 0.05;

        double hueBase = time * 12;

        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int idx = py * w + px;
                double dist = distTable[idx];
                double angle = angleTable[idx] + rotation;

                // Background: concentric rings breathing with bass
                double ringVal = Math.sin(dist * 20 - ringPhase) * 0.5 + 0.5;
                ringVal *= (0.2 + bass * 0.3);
                double bgBri = ringVal * 0.25;

                // Spiral arms
                double maxArmIntensity = 0;
                int dominantArm = 0;

                for (int arm = 0; arm < NUM_ARMS; arm++) {
                    // Golden spiral: r = a * e^(b*theta)
                    // Invert to find expected angle for this distance on this arm
                    double armOffset = arm * (2 * Math.PI / NUM_ARMS);
                    double spiralAngle = (angle - armOffset);

                    // How close is this pixel to the spiral arm?
                    // For a log spiral: theta = ln(r/a) / b
                    // We check how far the pixel's angle is from the expected angle
                    double expectedAngle = 0;
                    if (dist > 0.01) {
                        expectedAngle = Math.log(dist * 5 + 0.1) / LOG_GOLDEN;
                    }

                    double angleDiff = spiralAngle - expectedAngle;
                    // Normalize to [-pi, pi]
                    angleDiff = angleDiff % (2 * Math.PI);
                    if (angleDiff > Math.PI) angleDiff -= 2 * Math.PI;
                    if (angleDiff < -Math.PI) angleDiff += 2 * Math.PI;

                    // Arm width (narrower at center, wider at edges)
                    double armWidth = 0.3 + dist * 0.2 + bands[arm] * 0.2 + beatPulse * 0.15;
                    double armIntensity = Math.exp(-(angleDiff * angleDiff) / (armWidth * armWidth));

                    // Scale by band
                    armIntensity *= (0.3 + bands[arm] * 0.7);

                    if (armIntensity > maxArmIntensity) {
                        maxArmIntensity = armIntensity;
                        dominantArm = arm;
                    }
                }

                // Composite: background + arms
                double totalBri = bgBri + maxArmIntensity * 0.8 + amplitude * 0.05;
                totalBri = Math.min(1.0, totalBri);

                // Edge fade
                if (dist > 0.85) {
                    totalBri *= Math.max(0, (1.0 - dist) / 0.15);
                }

                float hue = (float) ((dominantArm * 72 + hueBase + dist * 60 + mid * 40) % 360);
                float sat = (float) (0.6 + maxArmIntensity * 0.35 + high * 0.05);
                sat = Math.min(1.0f, sat);

                if (totalBri < 0.01) {
                    pixels[idx] = 0xFF000000;
                } else {
                    pixels[idx] = BitmapFrameBuffer.fromHSB(hue, sat, (float) totalBri);
                }
            }
        }
    }

    @Override
    public void reset() {
        rotation = 0;
        beatPulse = 0;
        ringPhase = 0;
    }
}
