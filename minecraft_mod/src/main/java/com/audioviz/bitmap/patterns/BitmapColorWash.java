package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Full-screen color pulse that floods the display on beat.
 *
 * <p>On each beat, picks a new color from the dominant frequency band,
 * fills the screen, and fades to black between beats. Bass = warm colors,
 * mids = cool, highs = bright.
 */
public class BitmapColorWash extends BitmapPattern {

    private float currentHue = 0;
    private float targetHue = 0;
    private double washIntensity = 0;

    public BitmapColorWash() {
        super("bmp_colorwash", "Bitmap Color Wash",
              "Full-screen color flood that pulses on beat");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        double bass = audio.getBass();
        double mid = audio.getMid();
        double high = audio.getHigh();

        if (audio.isBeat()) {
            // Pick color from dominant band
            if (bass > mid && bass > high) {
                targetHue = 0 + (float) (bass * 40);    // Red/orange
            } else if (mid > high) {
                targetHue = 220 + (float) (mid * 40);   // Blue/purple
            } else {
                targetHue = 160 + (float) (high * 40);  // Cyan/white
            }
            washIntensity = 0.7 + audio.getBeatIntensity() * 0.3;
        }

        // Smooth hue transition
        float hueDiff = targetHue - currentHue;
        if (hueDiff > 180) hueDiff -= 360;
        if (hueDiff < -180) hueDiff += 360;
        currentHue += hueDiff * 0.15f;
        if (currentHue < 0) currentHue += 360;
        if (currentHue >= 360) currentHue -= 360;

        // Decay intensity
        washIntensity *= 0.92;

        float saturation = 0.8f + (float) (audio.getAmplitude() * 0.2);
        float brightness = (float) Math.min(1.0, washIntensity);

        if (brightness < 0.01) {
            buffer.clear();
            return;
        }

        int color = BitmapFrameBuffer.fromHSB(currentHue, saturation, brightness);

        // Subtle radial gradient: brighter at center
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        double cx = w / 2.0;
        double cy = h / 2.0;
        double maxDist = Math.sqrt(cx * cx + cy * cy);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dist = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
                double radialFade = 1.0 - (dist / maxDist) * 0.4; // 60-100% brightness
                int dimmed = BitmapFrameBuffer.lerpColor(
                    BitmapFrameBuffer.packARGB(255, 0, 0, 0), color, (float) (radialFade * washIntensity));
                buffer.setPixel(x, y, dimmed);
            }
        }
    }

    @Override
    public void reset() {
        currentHue = 0;
        targetHue = 0;
        washIntensity = 0;
    }
}
