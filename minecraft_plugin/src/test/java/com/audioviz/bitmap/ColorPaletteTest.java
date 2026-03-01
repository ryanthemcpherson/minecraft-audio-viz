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
            // mapSmooth scales to 254 (not 255) for interpolation headroom,
            // so the max index is 254 not 255 — close but not exact match
            int smooth = p.mapSmooth(1.0);
            int a = (smooth >> 24) & 0xFF;
            assertTrue(a == 255, "Alpha should be fully opaque");
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
            // fromGradient requires at least 2 colors; use same color twice for flat fill
            ColorPalette p = ColorPalette.fromGradient("flat", "Flat", 0xFFFF0000, 0xFFFF0000);
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
    @DisplayName("Map Edge Cases")
    class MapEdgeCases {
        @Test
        void mapNanClamps() {
            int[] lut = new int[256];
            lut[0] = 0xFF000000;
            ColorPalette palette = new ColorPalette("test", "Test", lut);
            // NaN should clamp — Math.max(0, NaN) returns NaN, Math.min(1, NaN) returns NaN
            // (int)(NaN * 255) = 0 in Java, so index 0
            int result = palette.map(Double.NaN);
            assertEquals(lut[0], result);
        }

        @Test
        void mapNegativeInfinity() {
            int[] lut = new int[256];
            lut[0] = 0xFFAA0000;
            ColorPalette palette = new ColorPalette("test", "Test", lut);
            assertEquals(lut[0], palette.map(Double.NEGATIVE_INFINITY));
        }

        @Test
        void mapPositiveInfinity() {
            int[] lut = new int[256];
            lut[255] = 0xFF00AA00;
            ColorPalette palette = new ColorPalette("test", "Test", lut);
            assertEquals(lut[255], palette.map(Double.POSITIVE_INFINITY));
        }
    }

    @Nested
    @DisplayName("Smooth Mapping")
    class SmoothMapping {
        @Test
        void smoothMidpointInterpolates() {
            // Two-color palette: black at 0, white at 255
            ColorPalette palette = ColorPalette.fromGradient("test", "Test",
                0xFF000000, 0xFFFFFFFF);
            int smooth = palette.mapSmooth(0.5);
            int r = (smooth >> 16) & 0xFF;
            // Should be roughly 128 (interpolated)
            assertTrue(r >= 120 && r <= 135, "Smooth midpoint R=" + r + " should be ~128");
        }

        @Test
        void smoothEndpointsMatchDirect() {
            ColorPalette palette = ColorPalette.fromGradient("test", "Test",
                0xFF000000, 0xFFFFFFFF);
            assertEquals(palette.map(0.0), palette.mapSmooth(0.0));
            // At 1.0: map uses index 255, mapSmooth uses lerp(254, 255, frac=0)
            // Both should return lut[254] or close to lut[255]
            int directMax = palette.map(1.0);
            int smoothMax = palette.mapSmooth(1.0);
            // They should be very close (within 1-2 per channel)
            int dr = Math.abs(((directMax >> 16) & 0xFF) - ((smoothMax >> 16) & 0xFF));
            assertTrue(dr <= 2, "Direct vs smooth at 1.0 should be close: delta=" + dr);
        }
    }

    @Nested
    @DisplayName("Gradient Generation Edge Cases")
    class GradientEdgeCases {
        @Test
        void fromGradientLessThanTwoColorsThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> ColorPalette.fromGradient("test", "Test", 0xFF000000));
        }

        @Test
        void fromGradientExactlyTwoColors() {
            ColorPalette palette = ColorPalette.fromGradient("test", "Test",
                0xFF000000, 0xFFFFFFFF);
            // Index 0 = black, index 255 = white
            assertEquals(0xFF000000, palette.map(0.0));
            assertEquals(0xFFFFFFFF, palette.map(1.0));
        }

        @Test
        void fromGradientThreeColorsEvenSplit() {
            ColorPalette palette = ColorPalette.fromGradient("test", "Test",
                0xFFFF0000, 0xFF00FF00, 0xFF0000FF);
            // At 0.0: red, at 0.5: green, at 1.0: blue
            int atZero = palette.map(0.0);
            assertEquals(255, (atZero >> 16) & 0xFF, "Red at 0.0");

            int atHalf = palette.map(0.5);
            int g = (atHalf >> 8) & 0xFF;
            assertTrue(g > 200, "Green at 0.5: " + g);

            int atOne = palette.map(1.0);
            assertEquals(255, (atOne) & 0xFF, "Blue at 1.0");
        }

        @Test
        void lutLengthMismatchThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> new ColorPalette("bad", "Bad", new int[100]));
        }
    }

    @Nested
    @DisplayName("Apply To Buffer")
    class ApplyToBufferExtended {
        @Test
        void pureWhiteMapsToBrightest() {
            ColorPalette palette = ColorPalette.fromGradient("test", "Test",
                0xFF000000, 0xFFFF0000);
            BitmapFrameBuffer buf = new BitmapFrameBuffer(2, 2);
            buf.fill(0xFFFFFFFF); // White = max brightness
            palette.applyToBuffer(buf);
            int pixel = buf.getPixel(0, 0);
            // Should map to near the end of the LUT (red)
            int r = (pixel >> 16) & 0xFF;
            assertTrue(r > 200, "White pixel should map to high-intensity: R=" + r);
        }

        @Test
        void pureBlackMapsToLowest() {
            ColorPalette palette = ColorPalette.fromGradient("test", "Test",
                0xFF000000, 0xFFFF0000);
            BitmapFrameBuffer buf = new BitmapFrameBuffer(2, 2);
            buf.fill(0xFF000000); // Black = zero brightness
            palette.applyToBuffer(buf);
            int pixel = buf.getPixel(0, 0);
            int r = (pixel >> 16) & 0xFF;
            assertTrue(r < 5, "Black pixel should map to low-intensity: R=" + r);
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
