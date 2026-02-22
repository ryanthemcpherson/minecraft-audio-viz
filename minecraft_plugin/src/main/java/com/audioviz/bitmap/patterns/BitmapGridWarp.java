package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Neon wireframe grid that warps and distorts with audio.
 *
 * <p>Horizontal and vertical grid lines are displaced by sine waves
 * whose amplitude follows bass energy. Neon cyan/magenta coloring.
 */
public class BitmapGridWarp extends BitmapPattern {

    private static final int COLOR_CYAN = BitmapFrameBuffer.rgb(0, 255, 230);
    private static final int COLOR_MAGENTA = BitmapFrameBuffer.rgb(255, 0, 200);
    private static final int BG = 0x00000000;

    public BitmapGridWarp() {
        super("bmp_gridwarp", "Bitmap Grid Warp",
              "Neon wireframe grid with audio-reactive distortion");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        buffer.fill(BG);

        double bass = audio.getBass();
        double mid = audio.getMid();
        double amplitude = audio.getAmplitude();
        double warpAmp = bass * 4.0 + 1.0;
        double scrollSpeed = time * (0.5 + mid * 2.0);

        int gridSpacing = Math.max(3, Math.min(w, h) / 8);

        // Draw warped horizontal lines
        for (int gridY = 0; gridY < h + gridSpacing; gridY += gridSpacing) {
            for (int x = 0; x < w; x++) {
                double nx = (double) x / w;
                double warp = Math.sin(nx * Math.PI * 3 + scrollSpeed) * warpAmp;
                warp += Math.sin(nx * Math.PI * 7 + scrollSpeed * 1.5) * warpAmp * 0.3;
                int py = gridY + (int) warp;
                if (py >= 0 && py < h) {
                    float glow = (float) (0.5 + amplitude * 0.5);
                    int color = BitmapFrameBuffer.lerpColor(BG, COLOR_CYAN, glow);
                    buffer.setPixel(x, py, color);
                    // Glow: dim adjacent pixels
                    if (py - 1 >= 0) {
                        int existing = buffer.getPixel(x, py - 1);
                        buffer.setPixel(x, py - 1, BitmapFrameBuffer.lerpColor(existing, COLOR_CYAN, glow * 0.3f));
                    }
                    if (py + 1 < h) {
                        int existing = buffer.getPixel(x, py + 1);
                        buffer.setPixel(x, py + 1, BitmapFrameBuffer.lerpColor(existing, COLOR_CYAN, glow * 0.3f));
                    }
                }
            }
        }

        // Draw warped vertical lines
        for (int gridX = 0; gridX < w + gridSpacing; gridX += gridSpacing) {
            for (int y = 0; y < h; y++) {
                double ny = (double) y / h;
                double warp = Math.sin(ny * Math.PI * 3 + scrollSpeed * 0.8) * warpAmp;
                warp += Math.sin(ny * Math.PI * 5 + scrollSpeed * 1.2) * warpAmp * 0.4;
                int px = gridX + (int) warp;
                if (px >= 0 && px < w) {
                    float glow = (float) (0.4 + amplitude * 0.6);
                    int color = BitmapFrameBuffer.lerpColor(BG, COLOR_MAGENTA, glow);
                    buffer.setPixel(px, y, color);
                }
            }
        }
    }

    @Override
    public void reset() {
        // Stateless
    }
}
