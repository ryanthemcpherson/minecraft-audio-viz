package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.concurrent.ThreadLocalRandom;

/**
 * TV static / digital noise / white noise visualization.
 *
 * <p>Renders random noise pixels that react to audio intensity. At low
 * amplitude, the display is mostly dark with scattered noise specks.
 * As amplitude increases, noise density and brightness ramp up toward
 * full white static. Beat hits trigger a flash to maximum noise that
 * quickly decays.
 *
 * <p>Audio reactivity:
 * <ul>
 *   <li>Amplitude controls noise density — low = sparse specks, high = full static</li>
 *   <li>Beat triggers a flash to full white noise that decays rapidly</li>
 *   <li>Dominant frequency band tints the noise color</li>
 *   <li>Bass adds scan-line artifacts (horizontal noise bars)</li>
 *   <li>High frequencies increase the noise refresh rate variation</li>
 * </ul>
 *
 * <p>Simple but iconic. Also useful as a transition or break effect
 * between other patterns.
 */
public class BitmapDigitalNoise extends BitmapPattern {

    /** Beat flash intensity, decays quickly. */
    private double beatFlash = 0.0;

    public BitmapDigitalNoise() {
        super("bmp_noise", "Digital Noise",
              "TV static and white noise reactive to audio intensity");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        ThreadLocalRandom rng = ThreadLocalRandom.current();

        double bass = audio.getBass();
        double mid = audio.getMid();
        double high = audio.getHigh();
        double amplitude = audio.getAmplitude();
        double[] bands = audio.getBands();

        // Beat flash: snaps to max, decays fast
        if (audio.isBeat()) {
            beatFlash = Math.max(beatFlash, 0.7 + audio.getBeatIntensity() * 0.3);
        } else {
            beatFlash *= 0.70; // Fast decay for punchy effect
        }

        // Effective noise intensity combines amplitude and beat flash
        double noiseIntensity = amplitude * 0.7 + beatFlash;
        noiseIntensity = Math.min(1.0, noiseIntensity);

        // Noise density: probability of a pixel being lit
        double density = 0.05 + noiseIntensity * 0.9;

        // Find dominant band for tint color
        int dominantBand = 0;
        double maxBandVal = 0;
        for (int i = 0; i < bands.length; i++) {
            if (bands[i] > maxBandVal) {
                maxBandVal = bands[i];
                dominantBand = i;
            }
        }

        // Tint based on dominant band
        double tintR = 1.0, tintG = 1.0, tintB = 1.0;
        if (maxBandVal > 0.3) {
            double tintStrength = (maxBandVal - 0.3) / 0.7; // 0-1
            switch (dominantBand) {
                case 0: // Bass → red tint
                    tintG = 1.0 - tintStrength * 0.5;
                    tintB = 1.0 - tintStrength * 0.6;
                    break;
                case 1: // Low-mid → orange tint
                    tintB = 1.0 - tintStrength * 0.7;
                    break;
                case 2: // Mid → green tint
                    tintR = 1.0 - tintStrength * 0.4;
                    tintB = 1.0 - tintStrength * 0.5;
                    break;
                case 3: // High-mid → cyan tint
                    tintR = 1.0 - tintStrength * 0.5;
                    break;
                case 4: // High → blue/violet tint
                    tintR = 1.0 - tintStrength * 0.3;
                    tintG = 1.0 - tintStrength * 0.5;
                    break;
            }
        }

        // --- Render noise ---
        for (int y = 0; y < h; y++) {
            // Scan-line artifact: bass creates horizontal bars of noise/darkness
            boolean scanLineActive = false;
            if (bass > 0.4) {
                double scanProb = (bass - 0.4) * 0.5;
                if (rng.nextDouble() < scanProb) {
                    scanLineActive = true;
                }
            }

            for (int x = 0; x < w; x++) {
                if (scanLineActive) {
                    // Scan line: either very bright or very dark
                    if (rng.nextBoolean()) {
                        int bri = 180 + rng.nextInt(76);
                        int r = (int) (bri * tintR);
                        int g = (int) (bri * tintG);
                        int b = (int) (bri * tintB);
                        buffer.setPixel(x, y, BitmapFrameBuffer.rgb(r, g, b));
                    } else {
                        buffer.setPixel(x, y, BitmapFrameBuffer.rgb(0, 0, 0));
                    }
                    continue;
                }

                if (rng.nextDouble() < density) {
                    // Lit pixel: random brightness scaled by noise intensity
                    double pixelBri = rng.nextDouble() * noiseIntensity;

                    // High frequencies add extra brightness variation
                    if (high > 0.3) {
                        pixelBri *= 0.5 + rng.nextDouble();
                    }

                    pixelBri = Math.min(1.0, pixelBri);

                    int bri = (int) (pixelBri * 255);
                    int r = (int) (bri * tintR);
                    int g = (int) (bri * tintG);
                    int b = (int) (bri * tintB);

                    buffer.setPixel(x, y, BitmapFrameBuffer.rgb(
                            Math.min(255, r), Math.min(255, g), Math.min(255, b)));
                } else {
                    // Dark pixel with very slight noise floor
                    int floor = rng.nextInt(6);
                    buffer.setPixel(x, y, BitmapFrameBuffer.rgb(floor, floor, floor));
                }
            }
        }

        // Beat flash overlay: brief bright wash
        if (beatFlash > 0.5) {
            double flashAlpha = (beatFlash - 0.5) * 2.0; // 0-1
            buffer.applyBrightness(1.0 + flashAlpha * 0.5); // Boost brightness
        }
    }

    @Override
    public void reset() {
        beatFlash = 0.0;
    }
}
