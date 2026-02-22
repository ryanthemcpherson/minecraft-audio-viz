package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Alternating checkerboard tiles that flash/invert on beat.
 *
 * <p>Two color sets swap on each beat, with fading between.
 * Colors derive from audio frequency content.
 */
public class BitmapCheckerboardFlash extends BitmapPattern {

    private boolean phase = false;
    private double flashBrightness = 0;
    private float hueA = 0;
    private float hueB = 180;

    public BitmapCheckerboardFlash() {
        super("bmp_checker", "Bitmap Checkerboard Flash",
              "Beat-synced checkerboard with color cycling");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        if (audio.isBeat()) {
            phase = !phase;
            flashBrightness = 1.0;

            // Shift colors on beat
            double[] bands = audio.getBands();
            int dominant = 0;
            for (int i = 1; i < bands.length; i++) {
                if (bands[i] > bands[dominant]) dominant = i;
            }
            hueA = dominant * 72.0f;
            hueB = (hueA + 180.0f) % 360.0f;
        }

        flashBrightness *= 0.88;

        // Tile size: adaptive to buffer dimensions
        int tileSize = Math.max(2, Math.min(w, h) / 8);

        // Slow hue cycling over time
        float hueShift = (float) (time * 15) % 360;

        for (int y = 0; y < h; y++) {
            int tileRow = y / tileSize;
            for (int x = 0; x < w; x++) {
                int tileCol = x / tileSize;
                boolean isOddTile = (tileRow + tileCol) % 2 == 0;

                // Phase determines which tiles are "active"
                boolean isActive = (isOddTile == phase);

                float hue = isActive ? (hueA + hueShift) % 360 : (hueB + hueShift) % 360;
                float sat = 0.85f;
                float bri;

                if (isActive) {
                    bri = (float) (0.6 + flashBrightness * 0.4);
                } else {
                    bri = (float) (0.08 + flashBrightness * 0.15);
                }

                bri = Math.min(1.0f, bri);
                int color = BitmapFrameBuffer.fromHSB(hue, sat, bri);
                buffer.setPixel(x, y, color);
            }
        }
    }

    @Override
    public void reset() {
        phase = false;
        flashBrightness = 0;
        hueA = 0;
        hueB = 180;
    }
}
