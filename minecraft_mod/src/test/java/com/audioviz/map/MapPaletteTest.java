package com.audioviz.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapPaletteTest {

    @Test
    void testRedMapsToFireColor() {
        // Pure red (255,0,0) → FIRE base (ID 4), shade 2 (brightest)
        // Map color ID = 4 * 4 + 2 = 18
        byte result = MapPalette.rgbToMapColor(255, 0, 0);
        assertEquals(18, Byte.toUnsignedInt(result));
    }

    @Test
    void testBlackMapsToNearestDark() {
        byte result = MapPalette.rgbToMapColor(0, 0, 0);
        // Should map to a valid (non-transparent) color
        assertTrue(Byte.toUnsignedInt(result) >= 4);
    }

    @Test
    void testTransparentPixelsMapToZero() {
        byte result = MapPalette.argbToMapColor(0x00000000); // fully transparent
        assertEquals(0, Byte.toUnsignedInt(result));
    }

    @Test
    void testSemiTransparentAboveThreshold() {
        // Alpha 200 (> 128) should be treated as opaque
        byte result = MapPalette.argbToMapColor(0xC8FF0000); // semi-transparent red
        assertTrue(Byte.toUnsignedInt(result) >= 4);
    }

    @Test
    void testSemiTransparentBelowThreshold() {
        // Alpha 100 (< 128) should be treated as transparent
        byte result = MapPalette.argbToMapColor(0x64FF0000);
        assertEquals(0, Byte.toUnsignedInt(result));
    }

    @Test
    void testLookupTableConsistency() {
        // Verify the precomputed table matches brute-force for colors
        // well within a palette entry's region (not on quantization boundaries).
        // Pure red (255,0,0) is dead-center of FIRE base color
        byte fast = MapPalette.rgbToMapColor(255, 0, 0);
        byte brute = MapPalette.rgbToMapColorBruteForce(255, 0, 0);
        assertEquals(brute, fast);

        // Pure white
        fast = MapPalette.rgbToMapColor(255, 255, 255);
        brute = MapPalette.rgbToMapColorBruteForce(255, 255, 255);
        assertEquals(brute, fast);

        // Dark color (near BLACK base)
        fast = MapPalette.rgbToMapColor(24, 24, 24);
        brute = MapPalette.rgbToMapColorBruteForce(24, 24, 24);
        assertEquals(brute, fast);
    }

    @Test
    void testWhiteMapsToSnow() {
        // Pure white → SNOW base (ID 8), shade 2 (brightest)
        // Map color ID = 8 * 4 + 2 = 34
        byte result = MapPalette.rgbToMapColor(255, 255, 255);
        assertEquals(34, Byte.toUnsignedInt(result));
    }

    @Test
    void testAllColorsAreValid() {
        // Every RGB value should map to a valid color (>= 4)
        for (int r = 0; r < 256; r += 17) {
            for (int g = 0; g < 256; g += 17) {
                for (int b = 0; b < 256; b += 17) {
                    byte result = MapPalette.rgbToMapColor(r, g, b);
                    assertTrue(Byte.toUnsignedInt(result) >= 4,
                        "RGB(" + r + "," + g + "," + b + ") mapped to transparent");
                }
            }
        }
    }
}
