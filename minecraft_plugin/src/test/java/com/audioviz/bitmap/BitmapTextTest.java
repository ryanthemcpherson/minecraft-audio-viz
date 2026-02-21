package com.audioviz.bitmap;

import com.audioviz.bitmap.text.BitmapFont;
import com.audioviz.bitmap.text.BitmapTextRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BitmapFont} and {@link BitmapTextRenderer}.
 * Tests glyph lookup, string measurement, and text drawing primitives.
 */
class BitmapTextTest {

    @Nested
    @DisplayName("BitmapFont Glyphs")
    class FontGlyphs {
        @Test
        void asciiPrintableCharsHaveGlyphs() {
            // All printable ASCII 32-126 should return non-null glyph
            for (char c = 32; c <= 126; c++) {
                byte[] glyph = BitmapFont.getGlyph(c);
                assertNotNull(glyph, "Glyph for char " + (int) c + " ('" + c + "') should exist");
            }
        }

        @Test
        void glyphHeight() {
            byte[] glyph = BitmapFont.getGlyph('A');
            assertEquals(BitmapFont.CHAR_HEIGHT, glyph.length,
                "Glyph should have CHAR_HEIGHT rows");
        }

        @Test
        void spaceGlyphIsBlank() {
            byte[] glyph = BitmapFont.getGlyph(' ');
            for (int y = 0; y < BitmapFont.CHAR_HEIGHT; y++) {
                for (int x = 0; x < BitmapFont.CHAR_WIDTH; x++) {
                    assertFalse(BitmapFont.isPixelSet(glyph, x, y),
                        "Space glyph should have no pixels set");
                }
            }
        }

        @Test
        void letterAHasPixels() {
            byte[] glyph = BitmapFont.getGlyph('A');
            boolean anySet = false;
            for (int y = 0; y < BitmapFont.CHAR_HEIGHT; y++) {
                for (int x = 0; x < BitmapFont.CHAR_WIDTH; x++) {
                    if (BitmapFont.isPixelSet(glyph, x, y)) {
                        anySet = true;
                        break;
                    }
                }
            }
            assertTrue(anySet, "Letter 'A' should have some pixels set");
        }

        @Test
        void nonPrintableReturnsFallback() {
            // Char 1 (non-printable) should return a glyph (fallback to space or block)
            byte[] glyph = BitmapFont.getGlyph((char) 1);
            assertNotNull(glyph, "Non-printable should return fallback glyph");
        }

        @Test
        void isPixelSetBoundsCheck() {
            byte[] glyph = BitmapFont.getGlyph('A');
            // Out of bounds should return false
            assertFalse(BitmapFont.isPixelSet(glyph, -1, 0));
            assertFalse(BitmapFont.isPixelSet(glyph, BitmapFont.CHAR_WIDTH, 0));
            assertFalse(BitmapFont.isPixelSet(glyph, 0, -1));
            assertFalse(BitmapFont.isPixelSet(glyph, 0, BitmapFont.CHAR_HEIGHT));
        }
    }

    @Nested
    @DisplayName("String Measurement")
    class Measurement {
        @Test
        void emptyStringWidthZero() {
            assertEquals(0, BitmapFont.measureString(""));
        }

        @Test
        void singleCharWidth() {
            assertEquals(BitmapFont.CHAR_WIDTH, BitmapFont.measureString("A"));
        }

        @Test
        void twoCharWidthIncludesSpacing() {
            int expected = BitmapFont.CHAR_WIDTH * 2 + BitmapFont.CHAR_SPACING;
            assertEquals(expected, BitmapFont.measureString("AB"));
        }

        @Test
        void helloWidth() {
            String text = "HELLO";
            int expected = text.length() * BitmapFont.CHAR_WIDTH
                + (text.length() - 1) * BitmapFont.CHAR_SPACING;
            assertEquals(expected, BitmapFont.measureString(text));
        }
    }

    @Nested
    @DisplayName("Text Rendering")
    class TextRendering {
        @Test
        void drawTextPlacesPixels() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(32, 16);
            BitmapTextRenderer.drawText(buf, "A", 0, 0, 0xFFFFFFFF);

            // At least one pixel in the glyph area should be white
            boolean found = false;
            for (int y = 0; y < BitmapFont.CHAR_HEIGHT; y++) {
                for (int x = 0; x < BitmapFont.CHAR_WIDTH; x++) {
                    if (buf.getPixel(x, y) == 0xFFFFFFFF) {
                        found = true;
                        break;
                    }
                }
            }
            assertTrue(found, "drawText should place visible pixels");
        }

        @Test
        void drawTextOffset() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(32, 16);
            BitmapTextRenderer.drawText(buf, "A", 10, 5, 0xFFFF0000);

            // Origin area should be empty
            assertEquals(0, buf.getPixel(0, 0), "Origin should be empty");

            // Something around (10,5) should have red
            boolean found = false;
            for (int y = 5; y < 5 + BitmapFont.CHAR_HEIGHT; y++) {
                for (int x = 10; x < 10 + BitmapFont.CHAR_WIDTH; x++) {
                    if (buf.getPixel(x, y) == 0xFFFF0000) {
                        found = true;
                        break;
                    }
                }
            }
            assertTrue(found, "Text should appear at offset (10, 5)");
        }

        @Test
        void drawTextCenteredOnSmallBuffer() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(32, 16);
            BitmapTextRenderer.drawTextCentered(buf, "HI", 0xFFFFFFFF);

            // "HI" is 11px wide (5+1+5). Centered in 32px: x starts at ~10
            // Check that something is near the center, not at the left edge
            boolean leftEmpty = buf.getPixel(0, 4) == 0;
            assertTrue(leftEmpty, "Left edge should be empty for centered text");

            // Something in the middle should be set
            boolean midSet = false;
            for (int x = 8; x < 24; x++) {
                for (int y = 4; y < 4 + BitmapFont.CHAR_HEIGHT; y++) {
                    if (buf.getPixel(x, y) != 0) {
                        midSet = true;
                        break;
                    }
                }
            }
            assertTrue(midSet, "Centered text should appear in middle region");
        }

        @Test
        void drawTextWithShadowProducesTwoLayers() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(32, 16);
            BitmapTextRenderer.drawTextWithShadow(buf, "A", 5, 5,
                0xFFFFFFFF, 0xFF404040);

            // Should have both white foreground and dark shadow pixels
            boolean hasWhite = false;
            boolean hasDark = false;
            for (int y = 5; y < 5 + BitmapFont.CHAR_HEIGHT + 1; y++) {
                for (int x = 5; x < 5 + BitmapFont.CHAR_WIDTH + 1; x++) {
                    int p = buf.getPixel(x, y);
                    if (p == 0xFFFFFFFF) hasWhite = true;
                    if (p == 0xFF404040) hasDark = true;
                }
            }
            assertTrue(hasWhite, "Should have foreground pixels");
            assertTrue(hasDark, "Should have shadow pixels");
        }

        @Test
        void drawTwoLinesFitOnBuffer() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(32, 16);
            BitmapTextRenderer.drawTwoLines(buf, "TOP", 0xFFFF0000,
                "BOT", 0xFF00FF00);

            // First line should have red pixels in top half
            boolean topRed = false;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 32; x++) {
                    if (buf.getPixel(x, y) == 0xFFFF0000) {
                        topRed = true;
                        break;
                    }
                }
            }

            // Second line should have green pixels in bottom half
            boolean botGreen = false;
            for (int y = 8; y < 16; y++) {
                for (int x = 0; x < 32; x++) {
                    if (buf.getPixel(x, y) == 0xFF00FF00) {
                        botGreen = true;
                        break;
                    }
                }
            }

            assertTrue(topRed, "Top line should have red pixels");
            assertTrue(botGreen, "Bottom line should have green pixels");
        }

        @Test
        void emptyTextRendersNothing() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(8, 8);
            BitmapTextRenderer.drawText(buf, "", 0, 0, 0xFFFFFFFF);

            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    assertEquals(0, buf.getPixel(x, y), "Empty text should leave buffer empty");
                }
            }
        }

        @Test
        void textClipsToBufferBounds() {
            // Text rendered at negative offset should not crash
            BitmapFrameBuffer buf = new BitmapFrameBuffer(8, 8);
            assertDoesNotThrow(() ->
                BitmapTextRenderer.drawText(buf, "HELLO WORLD", -20, -5, 0xFFFFFFFF));
        }
    }
}
