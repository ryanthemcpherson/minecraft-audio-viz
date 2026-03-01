package com.audioviz.bitmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class BitmapFrameBufferTest {

    // ========== Construction ==========

    @Nested
    class Construction {

        @Test
        void validDimensions() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(16, 16);
            assertEquals(16, buf.getWidth());
            assertEquals(16, buf.getHeight());
            assertEquals(256, buf.getPixelCount());
        }

        @ParameterizedTest
        @CsvSource({ "0, 10", "10, 0", "-1, 10", "10, -5", "0, 0" })
        void rejectsNonPositiveDimensions(int w, int h) {
            assertThrows(IllegalArgumentException.class, () -> new BitmapFrameBuffer(w, h));
        }

        @Test
        void rejectsOversizedFrame() {
            // 1025 * 1025 = 1_050_625 > 1_048_576
            assertThrows(IllegalArgumentException.class, () -> new BitmapFrameBuffer(1025, 1025));
        }

        @Test
        void acceptsMaxAllowedFrame() {
            // 1024 * 1024 = 1_048_576 == max
            assertDoesNotThrow(() -> new BitmapFrameBuffer(1024, 1024));
        }
    }

    // ========== Pixel Access ==========

    @Nested
    class PixelAccess {

        @Test
        void setGetRoundtripTopLeft() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(8, 8);
            int color = 0xFFAA5533;
            buf.setPixel(0, 0, color);
            assertEquals(color, buf.getPixel(0, 0));
        }

        @Test
        void setGetRoundtripBottomRight() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(10, 10);
            int color = 0xFF112233;
            buf.setPixel(9, 9, color);
            assertEquals(color, buf.getPixel(9, 9));
        }

        @Test
        void setGetRoundtripCenter() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(10, 10);
            int color = 0xFFDDEEFF;
            buf.setPixel(5, 5, color);
            assertEquals(color, buf.getPixel(5, 5));
        }

        @Test
        void setPixelWithComponents() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.setPixel(1, 2, 0xAA, 0xBB, 0xCC, 0xFF);
            int expected = BitmapFrameBuffer.packARGB(0xFF, 0xAA, 0xBB, 0xCC);
            assertEquals(expected, buf.getPixel(1, 2));
        }

        @Test
        void outOfBoundsSetPixelIsNoOp() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.setPixel(-1, 0, 0xFFFFFFFF);
            buf.setPixel(0, -1, 0xFFFFFFFF);
            buf.setPixel(4, 0, 0xFFFFFFFF);
            buf.setPixel(0, 4, 0xFFFFFFFF);
            // All pixels should still be zero (default)
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    assertEquals(0, buf.getPixel(x, y),
                        "Pixel at (" + x + "," + y + ") should be 0");
                }
            }
        }

        @Test
        void outOfBoundsGetPixelReturnsZero() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            assertEquals(0, buf.getPixel(-1, 0));
            assertEquals(0, buf.getPixel(0, -1));
            assertEquals(0, buf.getPixel(4, 0));
            assertEquals(0, buf.getPixel(0, 4));
        }

        @Test
        void getPixelFlatMatchesGetPixel() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 3);
            int color = 0xFF223344;
            buf.setPixel(2, 1, color);
            // index = y * width + x = 1 * 4 + 2 = 6
            assertEquals(color, buf.getPixelFlat(6));
        }

        @Test
        void getPixelFlatOutOfBoundsReturnsZero() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            assertEquals(0, buf.getPixelFlat(-1));
            assertEquals(0, buf.getPixelFlat(16));
        }
    }

    // ========== getRawPixels ==========

    @Test
    void getRawPixelsLengthEqualsWidthTimesHeight() {
        BitmapFrameBuffer buf = new BitmapFrameBuffer(7, 11);
        assertEquals(77, buf.getRawPixels().length);
    }

    @Test
    void getRawPixelsReflectsMutations() {
        BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
        buf.setPixel(3, 2, 0xFFAABBCC);
        int[] raw = buf.getRawPixels();
        assertEquals(0xFFAABBCC, raw[2 * 4 + 3]);
    }

    // ========== fill / clear ==========

    @Nested
    class FillAndClear {

        @Test
        void fillSetsAllPixels() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            int color = 0xFF112233;
            buf.fill(color);
            int[] raw = buf.getRawPixels();
            for (int pixel : raw) {
                assertEquals(color, pixel);
            }
        }

        @Test
        void clearResetsAllPixelsToZero() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.fill(0xFFFFFFFF);
            buf.clear();
            int[] raw = buf.getRawPixels();
            for (int pixel : raw) {
                assertEquals(0, pixel);
            }
        }

        @Test
        void clearWithArgbSetsAllPixels() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(3, 3);
            buf.clear(0xFF0000FF);
            for (int y = 0; y < 3; y++) {
                for (int x = 0; x < 3; x++) {
                    assertEquals(0xFF0000FF, buf.getPixel(x, y));
                }
            }
        }
    }

    // ========== fillRect ==========

    @Nested
    class FillRect {

        @Test
        void fillsInteriorPixels() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(8, 8);
            int color = 0xFF00FF00;
            buf.fillRect(2, 2, 3, 3, color);
            // Inside rect
            assertEquals(color, buf.getPixel(2, 2));
            assertEquals(color, buf.getPixel(4, 4));
            // Outside rect
            assertEquals(0, buf.getPixel(1, 1));
            assertEquals(0, buf.getPixel(5, 5));
        }

        @Test
        void clipsToBufferBoundsNegativeOrigin() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            int color = 0xFFFF0000;
            buf.fillRect(-2, -2, 4, 4, color);
            // Only the portion inside the buffer should be filled
            assertEquals(color, buf.getPixel(0, 0));
            assertEquals(color, buf.getPixel(1, 1));
            assertEquals(0, buf.getPixel(2, 0));
        }

        @Test
        void clipsToBufferBoundsOverflow() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            int color = 0xFFFF0000;
            buf.fillRect(2, 2, 10, 10, color);
            assertEquals(color, buf.getPixel(3, 3));
            assertEquals(0, buf.getPixel(1, 1));
        }

        @Test
        void zeroSizeRectIsNoOp() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.fillRect(0, 0, 0, 0, 0xFFFFFFFF);
            assertEquals(0, buf.getPixel(0, 0));
        }
    }

    // ========== drawLine (via fillColumn) ==========

    @Nested
    class FillColumn {

        @Test
        void fillColumnFullHeight() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            int color = 0xFFFF0000;
            buf.fillColumn(1, 1.0, color);
            for (int y = 0; y < 4; y++) {
                assertEquals(color, buf.getPixel(1, y));
            }
        }

        @Test
        void fillColumnZeroHeightIsNoOp() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.fillColumn(1, 0.0, 0xFFFF0000);
            for (int y = 0; y < 4; y++) {
                assertEquals(0, buf.getPixel(1, y));
            }
        }

        @Test
        void fillColumnOutOfBoundsIsNoOp() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.fillColumn(-1, 1.0, 0xFFFF0000);
            buf.fillColumn(4, 1.0, 0xFFFF0000);
            // No pixel should be changed
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    assertEquals(0, buf.getPixel(x, y));
                }
            }
        }

        @Test
        void fillColumnHalfHeightFillsFromBottom() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 8);
            int color = 0xFFAABBCC;
            buf.fillColumn(2, 0.5, color);
            // Top half should be empty
            for (int y = 0; y < 4; y++) {
                assertEquals(0, buf.getPixel(2, y), "Row " + y + " should be empty");
            }
            // Bottom half should be filled
            for (int y = 4; y < 8; y++) {
                assertEquals(color, buf.getPixel(2, y), "Row " + y + " should be filled");
            }
        }
    }

    // ========== Circle drawing bounds ==========

    @Nested
    class CircleDrawing {

        @Test
        void fillCircleStaysWithinBounds() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(10, 10);
            // Circle centered at (0,0) with radius 5 should clip to buffer
            buf.fillCircle(0, 0, 5, 0xFFFF0000);
            // Shouldn't throw, and no pixel outside buffer should be written
            // Verify some expected pixels
            assertEquals(0xFFFF0000, buf.getPixel(0, 0));
            assertNotEquals(0xFFFF0000, buf.getPixel(9, 9));
        }

        @Test
        void drawRingStaysWithinBounds() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(6, 6);
            // Ring at edge should clip safely — no IndexOutOfBoundsException
            buf.drawRing(0, 0, 10, 2, 0xFF00FF00);

            // Ring with center in buffer: radius 3, thickness 3 covers center
            buf.drawRing(3, 3, 3, 3, 0xFF00FF00);
            assertEquals(0xFF00FF00, buf.getPixel(3, 3));
        }
    }

    // ========== Color utilities ==========

    @Nested
    class ColorUtilities {

        @Test
        void packARGBRoundtrip() {
            int packed = BitmapFrameBuffer.packARGB(0xAA, 0xBB, 0xCC, 0xDD);
            assertEquals(0xAA, (packed >> 24) & 0xFF);
            assertEquals(0xBB, (packed >> 16) & 0xFF);
            assertEquals(0xCC, (packed >> 8) & 0xFF);
            assertEquals(0xDD, packed & 0xFF);
        }

        @Test
        void rgbSetsFullAlpha() {
            int color = BitmapFrameBuffer.rgb(0x11, 0x22, 0x33);
            assertEquals(0xFF, (color >> 24) & 0xFF);
            assertEquals(0x11, (color >> 16) & 0xFF);
            assertEquals(0x22, (color >> 8) & 0xFF);
            assertEquals(0x33, color & 0xFF);
        }

        @Test
        void lerpColorEndpoints() {
            int c1 = 0xFF000000;
            int c2 = 0xFFFFFFFF;
            assertEquals(c1, BitmapFrameBuffer.lerpColor(c1, c2, 0f));
            assertEquals(c2, BitmapFrameBuffer.lerpColor(c1, c2, 1f));
        }

        @Test
        void lerpColorMidpoint() {
            int c1 = 0xFF000000;
            int c2 = 0xFF646464; // R=100, G=100, B=100
            int mid = BitmapFrameBuffer.lerpColor(c1, c2, 0.5f);
            // Should be approximately 50 for each channel
            int r = (mid >> 16) & 0xFF;
            int g = (mid >> 8) & 0xFF;
            int b = mid & 0xFF;
            assertTrue(r >= 49 && r <= 51, "R channel: " + r);
            assertTrue(g >= 49 && g <= 51, "G channel: " + g);
            assertTrue(b >= 49 && b <= 51, "B channel: " + b);
        }

        @Test
        void lerpColorClampsOutOfRange() {
            int c1 = 0xFF000000;
            int c2 = 0xFFFFFFFF;
            assertEquals(c1, BitmapFrameBuffer.lerpColor(c1, c2, -1.0f));
            assertEquals(c2, BitmapFrameBuffer.lerpColor(c1, c2, 2.0f));
        }

        @Test
        void heatMapColorBoundaries() {
            int atZero = BitmapFrameBuffer.heatMapColor(0.0);
            assertEquals(0xFF, (atZero >> 24) & 0xFF, "Alpha should be full");

            int atOne = BitmapFrameBuffer.heatMapColor(1.0);
            assertEquals(0xFF, (atOne >> 24) & 0xFF, "Alpha should be full");

            // Should not throw for out-of-range
            assertDoesNotThrow(() -> BitmapFrameBuffer.heatMapColor(-0.5));
            assertDoesNotThrow(() -> BitmapFrameBuffer.heatMapColor(1.5));
        }
    }

    // ========== Scroll operations ==========

    @Nested
    class ScrollOps {

        @Test
        void scrollDownMovesTopRowDown() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.fill(0);
            // Fill row 0
            for (int x = 0; x < 4; x++) buf.setPixel(x, 0, 0xFFAAAAAA);
            buf.scrollDown();
            // Row 0 should now be cleared
            assertEquals(0, buf.getPixel(0, 0));
            // Row 1 should have the old row 0 data
            assertEquals(0xFFAAAAAA, buf.getPixel(0, 1));
        }

        @Test
        void scrollUpMovesBottomRowUp() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.fill(0);
            // Fill last row
            for (int x = 0; x < 4; x++) buf.setPixel(x, 3, 0xFFBBBBBB);
            buf.scrollUp();
            // Last row should be cleared
            assertEquals(0, buf.getPixel(0, 3));
            // Previous row should have the old last row data
            assertEquals(0xFFBBBBBB, buf.getPixel(0, 2));
        }

        @Test
        void scrollLeftMovesPixelsLeft() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.fill(0);
            buf.setPixel(3, 0, 0xFFCCCCCC);
            buf.scrollLeft();
            assertEquals(0xFFCCCCCC, buf.getPixel(2, 0));
            assertEquals(0, buf.getPixel(3, 0));
        }
    }

    // ========== loadPixels / setRow ==========

    @Nested
    class BulkLoad {

        @Test
        void loadPixelsOverwrites() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(2, 2);
            int[] data = { 0xFF111111, 0xFF222222, 0xFF333333, 0xFF444444 };
            buf.loadPixels(data);
            assertEquals(0xFF111111, buf.getPixel(0, 0));
            assertEquals(0xFF444444, buf.getPixel(1, 1));
        }

        @Test
        void loadPixelsRejectsSizeMismatch() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(2, 2);
            int[] data = { 0xFF111111, 0xFF222222 };
            assertThrows(IllegalArgumentException.class, () -> buf.loadPixels(data));
        }

        @Test
        void setRowClipsToWidth() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(3, 3);
            int[] longRow = { 0xFF111111, 0xFF222222, 0xFF333333, 0xFF444444, 0xFF555555 };
            buf.setRow(1, longRow);
            assertEquals(0xFF111111, buf.getPixel(0, 1));
            assertEquals(0xFF333333, buf.getPixel(2, 1));
        }

        @Test
        void setRowOutOfBoundsIsNoOp() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(3, 3);
            int[] row = { 0xFF111111, 0xFF222222, 0xFF333333 };
            buf.setRow(-1, row);
            buf.setRow(3, row);
            // Buffer should remain empty
            assertEquals(0, buf.getPixel(0, 0));
        }
    }

    // ========== applyBrightness ==========

    @Test
    void applyBrightnessScalesChannels() {
        BitmapFrameBuffer buf = new BitmapFrameBuffer(1, 1);
        buf.setPixel(0, 0, BitmapFrameBuffer.packARGB(0xFF, 200, 100, 50));
        buf.applyBrightness(0.5);
        int result = buf.getPixel(0, 0);
        assertEquals(0xFF, (result >> 24) & 0xFF, "Alpha should be unchanged");
        assertEquals(100, (result >> 16) & 0xFF, "R should be halved");
        assertEquals(50, (result >> 8) & 0xFF, "G should be halved");
        assertEquals(25, result & 0xFF, "B should be halved");
    }

    @Test
    void applyBrightnessOneIsNoOp() {
        BitmapFrameBuffer buf = new BitmapFrameBuffer(2, 2);
        int color = 0xFFAABBCC;
        buf.fill(color);
        buf.applyBrightness(1.0);
        assertEquals(color, buf.getPixel(0, 0));
    }
}
