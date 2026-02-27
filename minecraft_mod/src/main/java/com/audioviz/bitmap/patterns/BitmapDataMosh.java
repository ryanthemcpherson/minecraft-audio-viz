package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.Random;

/**
 * Data moshing / corrupted video glitch effect.
 *
 * <p>Renders a base gradient, then on beat captures a reference frame
 * and progressively smears/corrupts it. Rectangular block shifts,
 * row duplication, and color channel offsets.
 */
public class BitmapDataMosh extends BitmapPattern {

    private int[] referenceFrame;
    private double corruption = 0;
    private boolean hasReference = false;
    private int frameWidth, frameHeight;
    private final Random rng = new Random(41);

    public BitmapDataMosh() {
        super("bmp_datamosh", "Bitmap Data Mosh",
              "Corrupted video glitch with block smearing and color shifts");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        double amplitude = audio.getAmplitude();
        double bass = audio.getBass();

        // Render base gradient pattern (shifts with audio)
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double nx = (double) x / w;
                double ny = (double) y / h;
                float hue = (float) ((nx * 180 + ny * 90 + time * 30 + bass * 60) % 360);
                float bri = (float) (0.3 + amplitude * 0.4 + Math.sin(nx * 4 + time) * 0.15);
                buffer.setPixel(x, y, BitmapFrameBuffer.fromHSB(hue, 0.85f, Math.min(1, bri)));
            }
        }

        // On beat: capture new reference frame and reset corruption
        if (audio.isBeat()) {
            referenceFrame = buffer.getRawPixels().clone();
            frameWidth = w;
            frameHeight = h;
            hasReference = true;
            corruption = 0.1;
            rng.setSeed(audio.getFrame()); // New seed per beat for variety
        }

        if (!hasReference || frameWidth != w || frameHeight != h) return;

        // Increase corruption over time
        corruption = Math.min(1.0, corruption + 0.02 + amplitude * 0.03);

        int[] pixels = buffer.getRawPixels();
        int numGlitches = (int) (corruption * 8) + 1;

        for (int g = 0; g < numGlitches; g++) {
            int glitchType = rng.nextInt(3);
            switch (glitchType) {
                case 0: // Block shift: move a rectangular region horizontally
                    int blockY = rng.nextInt(h);
                    int blockH = 1 + rng.nextInt(Math.max(1, (int) (corruption * h / 4)));
                    int shift = rng.nextInt(Math.max(1, (int) (corruption * w / 2))) - (int) (corruption * w / 4);
                    for (int dy = 0; dy < blockH && blockY + dy < h; dy++) {
                        int row = (blockY + dy) * w;
                        for (int x = 0; x < w; x++) {
                            int srcX = x - shift;
                            if (srcX >= 0 && srcX < w) {
                                pixels[row + x] = referenceFrame[row + srcX];
                            }
                        }
                    }
                    break;

                case 1: // Row duplication
                    int srcRow = rng.nextInt(h);
                    int dstRow = rng.nextInt(h);
                    int numRows = 1 + rng.nextInt(Math.max(1, (int) (corruption * 3)));
                    for (int r = 0; r < numRows; r++) {
                        int sy = Math.min(srcRow + r, h - 1);
                        int dy = Math.min(dstRow + r, h - 1);
                        System.arraycopy(referenceFrame, sy * w, pixels, dy * w, w);
                    }
                    break;

                case 2: // Color channel shift on a region
                    int regionY = rng.nextInt(h);
                    int regionH = 2 + rng.nextInt(Math.max(1, (int) (corruption * h / 3)));
                    int channelShift = 1 + rng.nextInt(Math.max(1, (int) (corruption * 5)));
                    for (int dy = 0; dy < regionH && regionY + dy < h; dy++) {
                        int row = (regionY + dy) * w;
                        for (int x = 0; x < w; x++) {
                            int pixel = pixels[row + x];
                            int rx = Math.min(w - 1, x + channelShift);
                            int redSrc = pixels[row + rx];
                            // Swap red channel from offset position
                            pixel = (pixel & 0xFF00FFFF) | (redSrc & 0x00FF0000);
                            pixels[row + x] = pixel;
                        }
                    }
                    break;
            }
        }
    }

    @Override
    public void reset() {
        referenceFrame = null;
        hasReference = false;
        corruption = 0;
    }

    @Override
    public void onResize(int width, int height) {
        hasReference = false;
    }
}
