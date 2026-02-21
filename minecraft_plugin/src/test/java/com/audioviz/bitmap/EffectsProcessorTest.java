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
}
