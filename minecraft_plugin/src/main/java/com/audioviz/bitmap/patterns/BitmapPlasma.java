package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Audio-reactive plasma shader effect.
 *
 * <p>Classic demo-scene plasma adapted for LED walls. Multiple overlapping
 * sine waves create organic, flowing color fields. Audio reactivity:
 * <ul>
 *   <li>Bass controls wave speed and scale</li>
 *   <li>Mids shift the color palette hue</li>
 *   <li>Highs add fine-grain noise texture</li>
 *   <li>Beat hits cause a momentary zoom/pulse</li>
 * </ul>
 *
 * <p>Inspired by TheCymaera's RGB2DShader approach but driven by audio
 * instead of time alone.
 */
public class BitmapPlasma extends BitmapPattern {

    private double beatPulse = 0.0;
    private float[] centerDistTable;
    private int cachedWidth = -1;
    private int cachedHeight = -1;

    public BitmapPlasma() {
        super("bmp_plasma", "Bitmap Plasma",
              "Audio-reactive plasma shader with flowing color fields");
    }

    private void ensureDistTable(int w, int h) {
        if (w == cachedWidth && h == cachedHeight) return;
        cachedWidth = w;
        cachedHeight = h;
        centerDistTable = new float[w * h];
        for (int py = 0; py < h; py++) {
            double ny = (double) py / h;
            for (int px = 0; px < w; px++) {
                double nx = (double) px / w;
                centerDistTable[py * w + px] = (float) Math.sqrt((nx - 0.5) * (nx - 0.5) + (ny - 0.5) * (ny - 0.5));
            }
        }
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        ensureDistTable(w, h);

        double bass = audio.getBass();
        double mid = audio.getMid();
        double high = audio.getHigh();
        double amplitude = audio.getAmplitude();

        if (audio.isBeat()) {
            beatPulse = 1.0;
        } else {
            beatPulse *= 0.85;
        }

        double speed = time * (0.8 + bass * 2.0);
        double scale = 3.0 + beatPulse * 2.0;
        double hueShift = mid * 120.0;

        for (int py = 0; py < h; py++) {
            double ny = (double) py / h;

            for (int px = 0; px < w; px++) {
                double nx = (double) px / w;

                double v1 = Math.sin(nx * scale + speed);
                double v2 = Math.sin(ny * scale * 1.3 + speed * 0.7);
                double v3 = Math.sin((nx + ny) * scale * 0.7 + speed * 1.2);
                double v4 = Math.sin(centerDistTable[py * w + px] * scale * 2.0 + speed * 0.5);

                double plasma = (v1 + v2 + v3 + v4) / 4.0;
                plasma = (plasma + 1.0) / 2.0;

                if (high > 0.3) {
                    plasma += Math.sin(nx * 20 + ny * 20 + speed * 5) * high * 0.15;
                    plasma = Math.max(0, Math.min(1, plasma));
                }

                float hue = (float) ((plasma * 180.0 + hueShift + time * 30) % 360.0);
                float saturation = 0.8f + (float) (amplitude * 0.2);
                float brightness = 0.5f + (float) (amplitude * 0.3 + beatPulse * 0.2);
                brightness = Math.min(1.0f, brightness);

                int color = BitmapFrameBuffer.fromHSB(hue, saturation, brightness);
                buffer.setPixel(px, py, color);
            }
        }
    }

    @Override
    public void reset() {
        beatPulse = 0.0;
    }
}
