package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Tunnel zoom — concentric rectangles rushing toward the viewer.
 *
 * <p>Creates the illusion of flying through a neon tunnel.
 * Bass drives zoom speed, beats trigger forward jumps.
 */
public class BitmapTunnelZoom extends BitmapPattern {

    private double scrollOffset = 0;
    private double beatJump = 0;
    private float hueRotation = 0;

    public BitmapTunnelZoom() {
        super("bmp_tunnel", "Bitmap Tunnel Zoom",
              "Concentric rectangles creating a zoom/tunnel effect");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        double cx = w / 2.0;
        double cy = h / 2.0;

        double bass = audio.getBass();
        double amplitude = audio.getAmplitude();

        if (audio.isBeat()) {
            beatJump += 3.0 + audio.getBeatIntensity() * 5.0;
        }
        beatJump *= 0.85;

        double speed = 0.5 + bass * 2.0 + beatJump * 0.3;
        scrollOffset += speed * 0.05;
        hueRotation = (hueRotation + (float) (amplitude * 2.0)) % 360f;

        buffer.clear();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Distance from center using Chebyshev (max) distance for rectangular rings
                double dx = Math.abs(x - cx) / cx;
                double dy = Math.abs(y - cy) / cy;
                double dist = Math.max(dx, dy); // 0 at center, 1 at edge

                if (dist < 0.01) continue;

                // Create ring pattern with scroll offset
                double ringVal = dist * 10.0 + scrollOffset;
                double ring = ringVal % 2.0; // 0-2 cycle

                // Alternating bright/dark rings
                double intensity;
                if (ring < 0.15) {
                    // Ring edge: bright line
                    intensity = 0.8 + amplitude * 0.2;
                } else if (ring < 1.0) {
                    // Dark gap
                    intensity = 0.02 + amplitude * 0.05;
                } else if (ring < 1.15) {
                    // Alternate ring edge
                    intensity = 0.5 + amplitude * 0.3;
                } else {
                    intensity = 0.02 + amplitude * 0.03;
                }

                // Color: hue rotates with distance (creates rainbow tunnel)
                float hue = (float) ((dist * 180 + hueRotation + scrollOffset * 20) % 360);
                float sat = (ring < 0.15 || (ring > 1.0 && ring < 1.15)) ? 0.85f : 0.4f;
                float bri = (float) Math.min(1.0, intensity);

                // Perspective brightness: brighter near edges (closer to camera)
                bri *= (float) (0.3 + dist * 0.7);

                int color = BitmapFrameBuffer.fromHSB(hue, sat, bri);
                buffer.setPixel(x, y, color);
            }
        }
    }

    @Override
    public void reset() {
        scrollOffset = 0;
        beatJump = 0;
        hueRotation = 0;
    }
}
