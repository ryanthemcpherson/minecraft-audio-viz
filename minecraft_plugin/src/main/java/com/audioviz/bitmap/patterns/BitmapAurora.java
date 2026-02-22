package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Aurora borealis / northern lights curtain effect.
 *
 * <p>Multiple overlapping sine-wave curtains in green/cyan/purple
 * undulate across the top portion of the display. Audio drives
 * wave amplitude and brightness.
 */
public class BitmapAurora extends BitmapPattern {

    // Aurora palette: deep green, cyan, purple, blue
    private static final float[] CURTAIN_HUES = {130f, 170f, 280f, 200f};
    private static final double[] CURTAIN_FREQS = {1.5, 2.2, 1.8, 2.8};
    private static final double[] CURTAIN_SPEEDS = {0.3, -0.4, 0.25, -0.35};

    public BitmapAurora() {
        super("bmp_aurora", "Bitmap Aurora",
              "Northern lights curtains with audio-reactive motion");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        // Clear to transparent — curtain pixels are drawn over the Minecraft world
        buffer.clear();

        double mid = audio.getMid();
        double bass = audio.getBass();
        double high = audio.getHigh();
        double amplitude = audio.getAmplitude();

        // Draw 4 curtain layers
        for (int c = 0; c < 4; c++) {
            float hue = CURTAIN_HUES[c];
            double freq = CURTAIN_FREQS[c];
            double speed = CURTAIN_SPEEDS[c];
            double waveAmp = 0.08 + mid * 0.12; // 8-20% of height

            for (int x = 0; x < w; x++) {
                double nx = (double) x / w;

                // Curtain vertical position (oscillating sine wave)
                double baseY = 0.2 + 0.1 * c; // Each curtain at different base height
                double wave = Math.sin(nx * freq * Math.PI * 2 + time * speed * Math.PI * 2) * waveAmp;
                double wave2 = Math.sin(nx * freq * 1.7 * Math.PI * 2 + time * speed * 0.6 * Math.PI * 2) * waveAmp * 0.5;
                double curtainY = baseY + wave + wave2;

                // Curtain thickness varies with position
                double thickness = 0.08 + 0.04 * Math.sin(nx * 3 + time * 0.2);
                thickness += bass * 0.04;

                // Brightness varies along curtain (shimmer)
                double shimmer = 0.6 + 0.4 * Math.sin(nx * 8 + time * 2 + c);
                if (high > 0.3) {
                    shimmer += Math.sin(nx * 20 + time * 5) * high * 0.3;
                }

                double intensity = shimmer * (0.3 + amplitude * 0.5 + bass * 0.2);
                intensity = Math.min(1.0, intensity);

                // Draw curtain column
                int curtainPixelY = (int) (curtainY * h);
                int thicknessPx = Math.max(2, (int) (thickness * h));

                for (int dy = -thicknessPx / 2; dy <= thicknessPx / 2; dy++) {
                    int py = curtainPixelY + dy;
                    if (py < 0 || py >= h) continue;

                    double edgeFade = 1.0 - Math.abs((double) dy / (thicknessPx / 2.0));
                    edgeFade = edgeFade * edgeFade; // Smooth falloff
                    double pixelIntensity = intensity * edgeFade;
                    if (pixelIntensity < 0.02) continue;

                    float sat = 0.7f + (float) (amplitude * 0.3);
                    float bri = (float) Math.min(1.0, pixelIntensity);
                    int color = BitmapFrameBuffer.fromHSB(hue, sat, bri);

                    // Additive blend with existing pixel
                    int existing = buffer.getPixel(x, py);
                    int blended = additiveBlend(existing, color, pixelIntensity);
                    buffer.setPixel(x, py, blended);
                }
            }
        }
    }

    private static int additiveBlend(int base, int add, double intensity) {
        int r = Math.min(255, ((base >> 16) & 0xFF) + (int) (((add >> 16) & 0xFF) * intensity));
        int g = Math.min(255, ((base >> 8) & 0xFF) + (int) (((add >> 8) & 0xFF) * intensity));
        int b = Math.min(255, (base & 0xFF) + (int) ((add & 0xFF) * intensity));
        return BitmapFrameBuffer.packARGB(255, r, g, b);
    }

    @Override
    public void reset() {
        // Stateless
    }
}
