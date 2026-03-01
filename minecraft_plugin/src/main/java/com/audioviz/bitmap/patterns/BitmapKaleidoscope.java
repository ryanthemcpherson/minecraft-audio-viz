package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * 8-fold kaleidoscope symmetry pattern.
 *
 * <p>Generates a plasma-like source pattern in one wedge and mirrors it
 * 8 times for kaleidoscopic symmetry. Rotation follows BPM/beat phase.
 */
public class BitmapKaleidoscope extends BitmapPattern {

    private double rotation = 0;
    private double beatPulse = 0;
    private float[] distTable;
    private float[] angleTable;
    private int cachedWidth = -1;
    private int cachedHeight = -1;

    public BitmapKaleidoscope() {
        super("bmp_kaleidoscope", "Bitmap Kaleidoscope",
              "8-fold symmetry kaleidoscope with audio color cycling");
    }

    private void ensureLookupTables(int w, int h) {
        if (w == cachedWidth && h == cachedHeight) return;
        cachedWidth = w;
        cachedHeight = h;
        double cx = w / 2.0;
        double cy = h / 2.0;
        int size = w * h;
        distTable = new float[size];
        angleTable = new float[size];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = (x - cx) / cx;
                double dy = (y - cy) / cy;
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
        ensureLookupTables(w, h);

        double amplitude = audio.getAmplitude();
        double bass = audio.getBass();
        double mid = audio.getMid();

        if (audio.isBeat()) {
            beatPulse = 1.0;
        }
        beatPulse *= 0.9;

        rotation += 0.01 + amplitude * 0.03 + beatPulse * 0.05;

        double hueShift = time * 20 + mid * 60;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                double angle = angleTable[idx] + rotation;
                double dist = distTable[idx];

                double wedgeAngle = Math.abs(angle % (Math.PI / 4));
                if (((int) (angle / (Math.PI / 4))) % 2 != 0) {
                    wedgeAngle = Math.PI / 4 - wedgeAngle;
                }

                double u = wedgeAngle * 4;
                double v = dist;

                double v1 = Math.sin(u * 3 + time * 0.8 + bass * 2);
                double v2 = Math.sin(v * 5 + time * 1.1);
                double v3 = Math.sin((u + v) * 2.5 + time * 0.6);
                double plasma = (v1 + v2 + v3 + 3) / 6.0;

                float hue = (float) ((plasma * 240 + hueShift) % 360);
                float sat = 0.8f + (float) (amplitude * 0.2);
                float bri = (float) Math.min(1.0, plasma * 0.6 + amplitude * 0.3 + beatPulse * 0.2);

                if (dist > 0.9) {
                    bri *= (float) Math.max(0, (1.1 - dist) / 0.2);
                }

                int color = BitmapFrameBuffer.fromHSB(hue, sat, Math.max(0, bri));
                buffer.setPixel(x, y, color);
            }
        }
    }

    @Override
    public void reset() {
        rotation = 0;
        beatPulse = 0;
    }
}
