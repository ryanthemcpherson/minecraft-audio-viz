package com.audioviz.bitmap;

import com.audioviz.bitmap.effects.ColorPalette;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ColorPalette}.
 * Tests LUT mapping, smooth interpolation, gradient generation,
 * buffer application, and built-in palette integrity.
 */
class ColorPaletteTest {

    @Nested
    @DisplayName("LUT Mapping")
    class LutMapping {
        @Test
        void mapZeroReturnsFirstEntry() {
            int[] lut = new int[256];
            lut[0] = 0xFFFF0000;
            ColorPalette p = new ColorPalette("test", "Test", lut);
            assertEquals(0xFFFF0000, p.map(0.0));
        }

        @Test
        void mapOneReturnsLastEntry() {
            int[] lut = new int[256];
            lut[255] = 0xFF00FF00;
            ColorPalette p = new ColorPalette("test", "Test", lut);
            assertEquals(0xFF00FF00, p.map(1.0));
        }

        @Test
        void mapClampsNegative() {
            int[] lut = new int[256];
            lut[0] = 0xFFAABBCC;
            ColorPalette p = new ColorPalette("test", "Test", lut);
            assertEquals(0xFFAABBCC, p.map(-0.5));
        }

        @Test
        void mapClampsOverOne() {
            int[] lut = new int[256];
            lut[255] = 0xFFDDEEFF;
            ColorPalette p = new ColorPalette("test", "Test", lut);
            assertEquals(0xFFDDEEFF, p.map(1.5));
        }

        @Test
        void mapMidpoint() {
            int[] lut = new int[256];
            lut[127] = 0xFF888888;
            lut[128] = 0xFF999999;
            ColorPalette p = new ColorPalette("test", "Test", lut);
            // 0.5 * 255 ≈ 127
            int result = p.map(0.5);
            assertTrue(result == 0xFF888888 || result == 0xFF999999,
                "Midpoint should map to LUT index ~127-128");
        }
    }

    @Nested
    @DisplayName("Smooth Interpolation")
    class SmoothInterpolation {
        @Test
        void smoothEndpointsMatchMap() {
            ColorPalette p = ColorPalette.SPECTRUM;
            assertEquals(p.map(0.0), p.mapSmooth(0.0));
            assertEquals(p.map(1.0), p.mapSmooth(1.0));
        }

        @Test
        void smoothProducesValidColors() {
            ColorPalette p = ColorPalette.WARM;
            for (double i = 0; i <= 1.0; i += 0.01) {
                int color = p.mapSmooth(i);
                int a = (color >> 24) & 0xFF;
                assertTrue(a > 0, "Alpha should be non-zero for gradient palette at " + i);
            }
        }
    }

    @Nested
    @DisplayName("Gradient Generation")
    class GradientGeneration {
        @Test
        void twoColorGradient() {
            ColorPalette p = ColorPalette.fromGradient("test", "Test",
                0xFF000000, 0xFFFFFFFF);

            // Start should be black
            int start = p.map(0.0);
            assertEquals(0, (start >> 16) & 0xFF, "R at 0.0 should be 0");

            // End should be white
            int end = p.map(1.0);
            assertEquals(255, (end >> 16) & 0xFF, "R at 1.0 should be 255");
        }

        @Test
        void singleColorFillsEntireLut() {
            ColorPalette p = ColorPalette.fromGradient("flat", "Flat", 0xFFFF0000);
            assertEquals(0xFFFF0000, p.map(0.0));
            assertEquals(0xFFFF0000, p.map(0.5));
            assertEquals(0xFFFF0000, p.map(1.0));
        }

        @Test
        void lutLength256() {
            ColorPalette p = ColorPalette.fromGradient("test", "Test",
                0xFF000000, 0xFFFFFFFF);
            assertEquals(256, p.getLut().length);
        }
    }

    @Nested
    @DisplayName("Buffer Application")
    class BufferApplication {
        @Test
        void applyRemapsPixels() {
            // Create a palette that maps everything to red
            int[] lut = new int[256];
            java.util.Arrays.fill(lut, 0xFFFF0000);
            ColorPalette p = new ColorPalette("red", "All Red", lut);

            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.setPixel(0, 0, 0xFF808080); // Gray — will be treated as intensity
            p.applyToBuffer(buf);

            // After palette remap, pixel should be red
            assertEquals(0xFFFF0000, buf.getPixel(0, 0));
        }

        @Test
        void applySkipsTransparent() {
            int[] lut = new int[256];
            java.util.Arrays.fill(lut, 0xFFFF0000);
            ColorPalette p = new ColorPalette("red", "All Red", lut);

            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            // Pixel at (0,0) is 0 (transparent/black) — should map to lut[0]
            p.applyToBuffer(buf);
            assertEquals(0xFFFF0000, buf.getPixel(0, 0));
        }
    }

    @Nested
    @DisplayName("Built-in Palettes")
    class BuiltIn {
        @Test
        void allBuiltInsHaveIds() {
            for (ColorPalette p : ColorPalette.BUILT_IN) {
                assertNotNull(p.getId(), "Palette ID should not be null");
                assertFalse(p.getId().isEmpty(), "Palette ID should not be empty");
            }
        }

        @Test
        void allBuiltInsHaveNames() {
            for (ColorPalette p : ColorPalette.BUILT_IN) {
                assertNotNull(p.getName());
                assertFalse(p.getName().isEmpty());
            }
        }

        @Test
        void allBuiltInsHave256Entries() {
            for (ColorPalette p : ColorPalette.BUILT_IN) {
                assertEquals(256, p.getLut().length,
                    "Palette " + p.getId() + " should have 256 LUT entries");
            }
        }

        @Test
        void builtInCount() {
            // 10 built-in palettes
            assertEquals(10, ColorPalette.BUILT_IN.length);
        }

        @Test
        void uniqueIds() {
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (ColorPalette p : ColorPalette.BUILT_IN) {
                assertTrue(ids.add(p.getId()),
                    "Duplicate palette ID: " + p.getId());
            }
        }
    }
}
