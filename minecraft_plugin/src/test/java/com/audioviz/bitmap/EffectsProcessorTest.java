package com.audioviz.bitmap;

import com.audioviz.bitmap.effects.ColorPalette;
import com.audioviz.bitmap.effects.EffectsProcessor;
import com.audioviz.patterns.AudioState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EffectsProcessor}.
 * Tests the post-processing pipeline: brightness, blackout, freeze,
 * palette remapping, wash, strobe, and reset.
 */
class EffectsProcessorTest {

    private EffectsProcessor fx;

    @BeforeEach
    void setUp() {
        fx = new EffectsProcessor();
    }

    private BitmapFrameBuffer whiteBuffer() {
        BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
        buf.fill(0xFFFFFFFF);
        return buf;
    }

    @Nested
    @DisplayName("Brightness")
    class Brightness {
        @Test
        void defaultIsFullBrightness() {
            assertEquals(1.0, fx.getBrightness());
        }

        @Test
        void fullBrightnessNoChange() {
            BitmapFrameBuffer buf = whiteBuffer();
            fx.setBrightness(1.0);
            fx.process(buf, AudioState.forTest(0, false), 0);
            assertEquals(0xFFFFFFFF, buf.getPixel(0, 0));
        }

        @Test
        void zeroBrightnessIsBlack() {
            BitmapFrameBuffer buf = whiteBuffer();
            fx.setBrightness(0.0);
            fx.process(buf, AudioState.forTest(0, false), 0);

            int pixel = buf.getPixel(0, 0);
            assertEquals(0, (pixel >> 16) & 0xFF, "R should be 0");
            assertEquals(0, (pixel >> 8) & 0xFF, "G should be 0");
            assertEquals(0, pixel & 0xFF, "B should be 0");
        }

        @Test
        void halfBrightnessHalvesValues() {
            BitmapFrameBuffer buf = whiteBuffer();
            fx.setBrightness(0.5);
            fx.process(buf, AudioState.forTest(0, false), 0);

            int r = (buf.getPixel(0, 0) >> 16) & 0xFF;
            assertTrue(r >= 126 && r <= 129, "Half brightness R: " + r);
        }

        @Test
        void clampsToValidRange() {
            fx.setBrightness(-0.5);
            assertEquals(0.0, fx.getBrightness());

            fx.setBrightness(2.0);
            assertEquals(1.0, fx.getBrightness());
        }
    }

    @Nested
    @DisplayName("Blackout")
    class Blackout {
        @Test
        void blackoutZerosAllPixels() {
            BitmapFrameBuffer buf = whiteBuffer();
            fx.blackout(true);
            fx.process(buf, AudioState.forTest(0, false), 0);

            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    int pixel = buf.getPixel(x, y);
                    assertEquals(0, (pixel >> 16) & 0xFF, "R should be 0 during blackout");
                    assertEquals(0, (pixel >> 8) & 0xFF, "G should be 0 during blackout");
                    assertEquals(0, pixel & 0xFF, "B should be 0 during blackout");
                }
            }
        }

        @Test
        void unblackoutRestoresOriginal() {
            fx.blackout(true);
            fx.blackout(false);
            BitmapFrameBuffer buf = whiteBuffer();
            fx.process(buf, AudioState.forTest(0, false), 0);
            assertEquals(0xFFFFFFFF, buf.getPixel(0, 0));
        }
    }

    @Nested
    @DisplayName("Freeze")
    class Freeze {
        @Test
        void defaultNotFrozen() {
            assertFalse(fx.isFrozen());
        }

        @Test
        void freezeSnapshotsBuffer() {
            BitmapFrameBuffer buf = whiteBuffer();
            fx.freeze(buf);
            assertTrue(fx.isFrozen());
        }

        @Test
        void frozenBufferOverwritesInput() {
            // Freeze a red frame
            BitmapFrameBuffer redBuf = new BitmapFrameBuffer(4, 4);
            redBuf.fill(0xFFFF0000);
            fx.freeze(redBuf);

            // Process a blue frame — should get red (frozen)
            BitmapFrameBuffer blueBuf = new BitmapFrameBuffer(4, 4);
            blueBuf.fill(0xFF0000FF);
            fx.process(blueBuf, AudioState.forTest(0, false), 0);

            int r = (blueBuf.getPixel(0, 0) >> 16) & 0xFF;
            assertTrue(r > 200, "Should see red from frozen frame, got R=" + r);
        }

        @Test
        void unfreezeRestoresLiveInput() {
            BitmapFrameBuffer redBuf = new BitmapFrameBuffer(4, 4);
            redBuf.fill(0xFFFF0000);
            fx.freeze(redBuf);
            fx.unfreeze();

            BitmapFrameBuffer blueBuf = new BitmapFrameBuffer(4, 4);
            blueBuf.fill(0xFF0000FF);
            fx.process(blueBuf, AudioState.forTest(0, false), 0);

            int b = blueBuf.getPixel(0, 0) & 0xFF;
            assertEquals(255, b, "Should see live blue after unfreeze");
        }
    }

    @Nested
    @DisplayName("Palette")
    class PaletteEffect {
        @Test
        void defaultNoPalette() {
            assertNull(fx.getActivePalette());
            assertFalse(fx.isPaletteEnabled());
        }

        @Test
        void setPaletteEnablesRemapping() {
            fx.setPalette(ColorPalette.NEON);
            assertTrue(fx.isPaletteEnabled());
            assertEquals(ColorPalette.NEON, fx.getActivePalette());
        }

        @Test
        void clearPaletteDisablesRemapping() {
            fx.setPalette(ColorPalette.WARM);
            fx.clearPalette();
            assertFalse(fx.isPaletteEnabled());
        }

        @Test
        void paletteRemapsPixels() {
            // Create a palette that maps everything to green
            int[] lut = new int[256];
            java.util.Arrays.fill(lut, 0xFF00FF00);
            ColorPalette allGreen = new ColorPalette("green", "Green", lut);

            fx.setPalette(allGreen);

            BitmapFrameBuffer buf = whiteBuffer();
            fx.process(buf, AudioState.forTest(0, false), 0);

            assertEquals(0xFF00FF00, buf.getPixel(0, 0), "Palette should remap to green");
        }
    }

    @Nested
    @DisplayName("Color Wash")
    class Wash {
        @Test
        void washTintsBuffer() {
            BitmapFrameBuffer buf = whiteBuffer();
            fx.setWash(0xFFFF0000, 0.5); // 50% red wash
            fx.process(buf, AudioState.forTest(0, false), 0);

            int r = (buf.getPixel(0, 0) >> 16) & 0xFF;
            int g = (buf.getPixel(0, 0) >> 8) & 0xFF;

            // Red channel should remain high (white + red wash)
            assertTrue(r > 200, "R should be high: " + r);
            // Green channel should be reduced by the red wash overlay
            assertTrue(g < 200, "G should be reduced by wash: " + g);
        }

        @Test
        void clearWashRestores() {
            fx.setWash(0xFFFF0000, 1.0);
            fx.clearWash();
            BitmapFrameBuffer buf = whiteBuffer();
            fx.process(buf, AudioState.forTest(0, false), 0);
            assertEquals(0xFFFFFFFF, buf.getPixel(0, 0));
        }
    }

    @Nested
    @DisplayName("Strobe")
    class Strobe {
        @Test
        void defaultDisabled() {
            assertFalse(fx.isStrobeEnabled());
        }

        @Test
        void enableAndDisable() {
            fx.setStrobeEnabled(true);
            assertTrue(fx.isStrobeEnabled());

            fx.setStrobeEnabled(false);
            assertFalse(fx.isStrobeEnabled());
        }

        @Test
        void strobeFlashesOnBeat() {
            fx.setStrobeEnabled(true);
            fx.setStrobeColor(0xFFFFFFFF);

            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.fill(0xFF000000); // Start black

            // Process with a beat
            AudioState beatAudio = AudioState.forTest(0, true);
            fx.process(buf, beatAudio, 0);

            // On a beat with strobe, should flash to strobe color
            int pixel = buf.getPixel(0, 0);
            int r = (pixel >> 16) & 0xFF;
            // Strobe may not be full white depending on implementation,
            // but should be brighter than the original black
            assertTrue(r > 0 || (pixel & 0xFF) > 0 || ((pixel >> 8) & 0xFF) > 0,
                "Strobe should produce visible output on beat");
        }
    }

    @Nested
    @DisplayName("Reset")
    class Reset {
        @Test
        void resetClearsAllEffects() {
            fx.setStrobeEnabled(true);
            fx.setBrightness(0.5);
            fx.blackout(true);
            fx.setPalette(ColorPalette.LAVA);
            fx.setWash(0xFFFF0000, 0.5);
            fx.setBeatFlashEnabled(true);

            fx.reset();

            assertFalse(fx.isStrobeEnabled());
            assertEquals(1.0, fx.getBrightness());
            assertFalse(fx.isFrozen());
            assertFalse(fx.isPaletteEnabled());

            // After reset, processing a white buffer should leave it white
            BitmapFrameBuffer buf = whiteBuffer();
            fx.process(buf, AudioState.forTest(0, false), 0);
            assertEquals(0xFFFFFFFF, buf.getPixel(0, 0));
        }
    }

    @Nested
    @DisplayName("Processing Order")
    class ProcessingOrder {
        @Test
        void brightnessAppliedAfterPalette() {
            // Set a palette that maps to white
            int[] lut = new int[256];
            java.util.Arrays.fill(lut, 0xFFFFFFFF);
            fx.setPalette(new ColorPalette("white", "White", lut));

            // Set brightness to half
            fx.setBrightness(0.5);

            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.fill(0xFF808080);
            fx.process(buf, AudioState.forTest(0, false), 0);

            // Palette remaps to white, then brightness halves it
            int r = (buf.getPixel(0, 0) >> 16) & 0xFF;
            assertTrue(r >= 126 && r <= 129,
                "Should be half-bright white after palette + brightness: " + r);
        }
    }

    @Nested
    @DisplayName("RGB Split Effect")
    class RgbSplit {
        @Test
        void rgbSplitSeparatesChannels() {
            EffectsProcessor proc = new EffectsProcessor();
            proc.setRgbSplitEnabled(true);
            proc.setRgbSplitOffset(1);

            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 1);
            // Set pixel at (1,0) to white; others black
            buf.setPixel(1, 0, 0xFFFFFFFF);
            proc.process(buf, null, 0);

            // Red channel shifts: pixel(2,0) gets red from x-1=1 (white)
            int right = buf.getPixel(2, 0);
            int rRight = (right >> 16) & 0xFF;
            assertEquals(255, rRight, "Red should appear at x+offset from source");

            // Blue channel shifts: pixel(0,0) gets blue from x+1=1 (white)
            int left = buf.getPixel(0, 0);
            int bLeft = left & 0xFF;
            assertEquals(255, bLeft, "Blue should appear at x-offset from source");
        }

        @Test
        void rgbSplitDisabledNoChange() {
            EffectsProcessor proc = new EffectsProcessor();
            proc.setRgbSplitEnabled(false);
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 1);
            buf.setPixel(1, 0, 0xFFFFFFFF);
            int before = buf.getPixel(0, 0);
            proc.process(buf, null, 0);
            assertEquals(before, buf.getPixel(0, 0), "Disabled RGB split should not modify");
        }
    }

    @Nested
    @DisplayName("Bit Crush Effect")
    class BitCrush {
        @Test
        void colorQuantization() {
            EffectsProcessor proc = new EffectsProcessor();
            proc.setBitCrushEnabled(true);
            proc.setBitCrushColorLevels(2); // Only 0 or 255
            proc.setBitCrushPixelSize(1);   // No spatial downsampling

            BitmapFrameBuffer buf = new BitmapFrameBuffer(1, 1);
            buf.fill(BitmapFrameBuffer.packARGB(255, 100, 200, 50));
            proc.process(buf, null, 0);

            int pixel = buf.getPixel(0, 0);
            // With 2 levels: each channel rounds to 0 or 255
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            assertTrue(r == 0 || r == 255, "R should be quantized to 0 or 255: " + r);
            assertTrue(g == 0 || g == 255, "G should be quantized to 0 or 255: " + g);
            assertTrue(b == 0 || b == 255, "B should be quantized to 0 or 255: " + b);
        }

        @Test
        void spatialDownsampling() {
            EffectsProcessor proc = new EffectsProcessor();
            proc.setBitCrushEnabled(true);
            proc.setBitCrushColorLevels(256); // No color change
            proc.setBitCrushPixelSize(2);

            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.setPixel(0, 0, 0xFFFF0000);
            buf.setPixel(1, 0, 0xFF00FF00);
            buf.setPixel(0, 1, 0xFF0000FF);
            buf.setPixel(1, 1, 0xFFFFFFFF);
            proc.process(buf, null, 0);

            // In a 2x2 block, all should match the leader (0,0)
            assertEquals(buf.getPixel(0, 0), buf.getPixel(1, 0), "Block should match leader");
            assertEquals(buf.getPixel(0, 0), buf.getPixel(0, 1), "Block should match leader");
            assertEquals(buf.getPixel(0, 0), buf.getPixel(1, 1), "Block should match leader");
        }
    }

    @Nested
    @DisplayName("Edge Flash Effect")
    class EdgeFlash {
        @Test
        void edgeFlashAffectsEdgesOnly() {
            EffectsProcessor proc = new EffectsProcessor();
            proc.setEdgeFlashEnabled(true);
            proc.setEdgeFlashColor(0xFFFFFFFF);
            proc.setEdgeFlashWidth(1);

            BitmapFrameBuffer buf = new BitmapFrameBuffer(8, 8);
            buf.fill(0xFF000000); // All black

            // Simulate beat to trigger edge flash
            AudioState beat = new AudioState(new double[5], 0.5, true, 0.8, 1);
            proc.process(buf, beat, 0);

            // Edge pixel should be modified (blended toward white)
            int edge = buf.getPixel(0, 0);
            assertTrue(((edge >> 16) & 0xFF) > 0, "Edge should be brightened");

            // Center pixel should still be black (distance from edge = 3)
            int center = buf.getPixel(4, 4);
            assertEquals(0xFF000000, center, "Center should be unaffected");
        }
    }

    @Nested
    @DisplayName("Strobe Decay")
    class StrobeDecay {
        @Test
        void strobeDecaysAcrossFrames() {
            EffectsProcessor proc = new EffectsProcessor();
            proc.setStrobeEnabled(true);
            // Use divisor > 1 so the strobe doesn't re-trigger on every frame
            // divisor=2: fires when strobeBeatCount % 2 == 0
            // After 2 beats, count=2, 2%2=0 triggers. After that, no more beats so count stays 2 and re-triggers.
            // Instead: just use default divisor=1, trigger once, then check the decay value via pixel brightness.
            // Since divisor=1 always re-triggers: to test decay, we compare the *accumulated* flash on a non-zero buffer.
            // Alternative: verify strobe produces visible output, then disable strobe and check decay stops.

            // Approach: fire one beat to start strobe, then measure decay by capturing strobeDecay
            // through the pixel output on subsequent frames with strobe disabled between frames.

            // Simplest approach that works with the production code:
            // 1) Process with beat (strobeDecay set to 1.0, then *= 0.7 = 0.7 after flash)
            // 2) Disable strobe before next frame so it doesn't re-trigger
            // 3) Process without beat — strobeDecay > 0.01 still applies flash but weaker

            BitmapFrameBuffer buf = new BitmapFrameBuffer(1, 1);
            AudioState beat = new AudioState(new double[5], 0.5, true, 0.8, 1);

            buf.fill(0xFF000000);
            proc.process(buf, beat, 0);
            int flash1 = buf.getPixel(0, 0);

            // Disable strobe so it won't re-trigger strobeDecay=1.0
            proc.setStrobeEnabled(false);

            // Second frame — no beat, strobe disabled so won't re-trigger, but residual decay still applies
            buf.fill(0xFF000000);
            AudioState noBeat = new AudioState(new double[5], 0.5, false, 0.0, 2);
            proc.process(buf, noBeat, 1);
            int flash2 = buf.getPixel(0, 0);

            int brightness1 = ((flash1 >> 16) & 0xFF) + ((flash1 >> 8) & 0xFF) + (flash1 & 0xFF);
            int brightness2 = ((flash2 >> 16) & 0xFF) + ((flash2 >> 8) & 0xFF) + (flash2 & 0xFF);
            assertTrue(brightness2 < brightness1, "Strobe should decay: " + brightness2 + " >= " + brightness1);
        }
    }

    @Nested
    @DisplayName("Null Audio Safety")
    class NullAudio {
        @Test
        void processWithNullAudioDoesNotCrash() {
            EffectsProcessor proc = new EffectsProcessor();
            proc.setStrobeEnabled(true);
            proc.setBeatFlashEnabled(true);
            proc.setEdgeFlashEnabled(true);
            proc.setRgbSplitEnabled(true);
            proc.setBitCrushEnabled(true);

            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.fill(0xFFFFFFFF);
            assertDoesNotThrow(() -> proc.process(buf, null, 0));
        }
    }
}
