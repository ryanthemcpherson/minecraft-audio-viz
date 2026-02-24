package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Julia set fractal with continuous zoom and audio-driven parameters.
 *
 * <p>Psychedelic fractal that continuously zooms in, with the Julia set
 * 'c' parameter modulated by audio. Hue cycles with iteration count.
 */
public class BitmapFractalZoom extends BitmapPattern {

    private double zoom = 1.0;
    private double centerX = -0.5;
    private double centerY = 0.0;
    private double beatPulse = 0;

    private static final int MAX_ITER = 24;

    public BitmapFractalZoom() {
        super("bmp_fractal", "Bitmap Fractal Zoom",
              "Audio-reactive Julia set with continuous zoom");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        double bass = audio.getBass();
        double mid = audio.getMid();
        double amplitude = audio.getAmplitude();

        if (audio.isBeat()) {
            beatPulse = 1.0;
        }
        beatPulse *= 0.9;

        // Continuous zoom
        zoom *= 1.0 + (0.002 + bass * 0.01 + beatPulse * 0.05);
        if (zoom > 1000) {
            zoom = 1.0; // Reset when too deep
            centerX = -0.5 + Math.sin(time * 0.1) * 0.3;
            centerY = Math.cos(time * 0.13) * 0.3;
        }

        // Precompute time-dependent values outside pixel loops
        double cRe = -0.7 + Math.sin(time * 0.15) * 0.15 + mid * 0.05;
        double cIm = 0.27 + Math.cos(time * 0.12) * 0.1 + bass * 0.03;
        double scale = 3.0 / zoom;
        double hueShift = time * 20;
        double halfW = w / 2.0;
        double halfH = h / 2.0;
        double invW = scale / w;
        double invH = scale / h;
        double log2 = Math.log(2);

        for (int py = 0; py < h; py++) {
            double y0 = centerY + (py - halfH) * invH;
            for (int px = 0; px < w; px++) {
                double x0 = centerX + (px - halfW) * invW;

                double zr = x0, zi = y0;
                int iter = 0;
                while (iter < MAX_ITER && zr * zr + zi * zi < 4.0) {
                    double tmp = zr * zr - zi * zi + cRe;
                    zi = 2.0 * zr * zi + cIm;
                    zr = tmp;
                    iter++;
                }

                if (iter < MAX_ITER) {
                    double mag = Math.sqrt(zr * zr + zi * zi);
                    double smoothIter = iter + 1 - Math.log(Math.log(Math.max(1.0001, mag))) / log2;
                    float hue = (float) ((smoothIter * 8 + hueShift) % 360);
                    float sat = 0.8f + (float) (amplitude * 0.2);
                    float bri = (float) Math.min(1.0, 0.5 + amplitude * 0.3 + beatPulse * 0.2);
                    buffer.setPixel(px, py, BitmapFrameBuffer.fromHSB(hue, sat, bri));
                }
            }
        }
    }

    @Override
    public void reset() {
        zoom = 1.0;
        centerX = -0.5;
        centerY = 0.0;
        beatPulse = 0;
    }
}
