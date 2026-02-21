package com.audioviz.bitmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BitmapFrameBuffer}.
 * Tests pixel operations, drawing primitives, color math, and bounds safety.
 */
class BitmapFrameBufferTest {

    @Nested
    @DisplayName("Construction")
    class Construction {
        @Test
        void validDimensions() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(32, 16);
            assertEquals(32, buf.getWidth());
            assertEquals(16, buf.getHeight());
            assertEquals(512, buf.getPixelCount());
        }

        @Test
        void zeroDimensionThrows() {
            assertThrows(IllegalArgumentException.class, () -> new BitmapFrameBuffer(0, 16));
            assertThrows(IllegalArgumentException.class, () -> new BitmapFrameBuffer(32, 0));
        }

        @Test
        void tooLargeThrows() {
            // 10_000 pixel limit
            assertThrows(IllegalArgumentException.class, () -> new BitmapFrameBuffer(200, 200));
        }

        @Test
        void maxSizeOk() {
            // 100x100 = 10_000 exactly — should be fine
            assertDoesNotThrow(() -> new BitmapFrameBuffer(100, 100));
        }

        @Test
        void startsBlack() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    assertEquals(0, buf.getPixel(x, y), "Pixel should start at 0");
                }
            }
        }
    }

    @Nested
    @DisplayName("Pixel Access")
    class PixelAccess {
        @Test
        void setAndGet() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(8, 8);
            buf.setPixel(3, 5, 0xFFFF0000);
            assertEquals(0xFFFF0000, buf.getPixel(3, 5));
        }

        @Test
        void outOfBoundsGetReturnsZero() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            assertEquals(0, buf.getPixel(-1, 0));
            assertEquals(0, buf.getPixel(4, 0));
            assertEquals(0, buf.getPixel(0, -1));
            assertEquals(0, buf.getPixel(0, 4));
        }

        @Test
        void outOfBoundsSetNoOp() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.setPixel(-1, 0, 0xFFFF0000); // Should not crash
            buf.setPixel(100, 100, 0xFFFF0000); // Should not crash
            assertEquals(0, buf.getPixel(0, 0)); // Unchanged
        }

        @Test
        void rawPixelsAccess() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.setPixel(0, 0, 0xDEADBEEF);
            int[] raw = buf.getRawPixels();
            assertEquals(0xDEADBEEF, raw[0]);
            assertEquals(16, raw.length);
        }
    }

    @Nested
    @DisplayName("Clear and Fill")
    class ClearAndFill {
        @Test
        void clearToTransparent() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.setPixel(0, 0, 0xFFFFFFFF);
            buf.clear();
            assertEquals(0, buf.getPixel(0, 0));
        }

        @Test
        void clearToColor() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.clear(0xFF112233);
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    assertEquals(0xFF112233, buf.getPixel(x, y));
                }
            }
        }

        @Test
        void fillRect() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(8, 8);
            buf.fillRect(2, 2, 3, 3, 0xFFFF0000);

            // Inside rect
            assertEquals(0xFFFF0000, buf.getPixel(2, 2));
            assertEquals(0xFFFF0000, buf.getPixel(4, 4));

            // Outside rect
            assertEquals(0, buf.getPixel(1, 1));
            assertEquals(0, buf.getPixel(5, 5));
        }

        @Test
        void fillRectClipsToBuffer() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            // Rect extends past right and bottom edges
            buf.fillRect(2, 2, 10, 10, 0xFFFF0000);
            assertEquals(0xFFFF0000, buf.getPixel(3, 3));
            assertEquals(0, buf.getPixel(1, 1));
        }

        @Test
        void fillEntire() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.fill(0xFF00FF00);
            assertEquals(0xFF00FF00, buf.getPixel(0, 0));
            assertEquals(0xFF00FF00, buf.getPixel(3, 3));
        }
    }

    @Nested
    @DisplayName("Color Math")
    class ColorMath {
        @Test
        void packARGB() {
            int color = BitmapFrameBuffer.packARGB(255, 128, 64, 32);
            assertEquals(255, (color >> 24) & 0xFF);
            assertEquals(128, (color >> 16) & 0xFF);
            assertEquals(64, (color >> 8) & 0xFF);
            assertEquals(32, color & 0xFF);
        }

        @Test
        void lerpColorEndpoints() {
            int black = 0xFF000000;
            int white = 0xFFFFFFFF;

            assertEquals(black, BitmapFrameBuffer.lerpColor(black, white, 0.0f));
            assertEquals(white, BitmapFrameBuffer.lerpColor(black, white, 1.0f));
        }

        @Test
        void lerpColorMidpoint() {
            int black = 0xFF000000;
            int white = 0xFFFFFFFF;

            int mid = BitmapFrameBuffer.lerpColor(black, white, 0.5f);
            int r = (mid >> 16) & 0xFF;
            int g = (mid >> 8) & 0xFF;
            int b = mid & 0xFF;

            // Should be approximately 127-128
            assertTrue(r >= 126 && r <= 129, "R channel: " + r);
            assertTrue(g >= 126 && g <= 129, "G channel: " + g);
            assertTrue(b >= 126 && b <= 129, "B channel: " + b);
        }

        @Test
        void lerpColorClampsT() {
            int c1 = 0xFF000000;
            int c2 = 0xFFFFFFFF;

            // t < 0 should clamp to 0
            assertEquals(c1, BitmapFrameBuffer.lerpColor(c1, c2, -1.0f));
            // t > 1 should clamp to 1
            assertEquals(c2, BitmapFrameBuffer.lerpColor(c1, c2, 2.0f));
        }

        @Test
        void lerpColorDoubleOverload() {
            int c1 = 0xFF000000;
            int c2 = 0xFFFFFFFF;

            int fromFloat = BitmapFrameBuffer.lerpColor(c1, c2, 0.5f);
            int fromDouble = BitmapFrameBuffer.lerpColor(c1, c2, 0.5);
            assertEquals(fromFloat, fromDouble, "Double and float overloads should match");
        }
    }

    @Nested
    @DisplayName("Drawing Primitives")
    class Drawing {
        @Test
        void fillCircle() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(16, 16);
            buf.fillCircle(8, 8, 3, 0xFFFF0000);

            // Center should be filled
            assertEquals(0xFFFF0000, buf.getPixel(8, 8));
            // Corner should not be (distance > 3)
            assertEquals(0, buf.getPixel(0, 0));
        }

        @Test
        void scrollDown() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.setPixel(0, 0, 0xFFFF0000);
            buf.scrollDown();

            assertEquals(0, buf.getPixel(0, 0), "Top row should be cleared");
            assertEquals(0xFFFF0000, buf.getPixel(0, 1), "Pixel should have moved down 1 row");
        }

        @Test
        void scrollUp() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.setPixel(0, 3, 0xFFFF0000);
            buf.scrollUp();

            assertEquals(0, buf.getPixel(0, 3), "Bottom row should be cleared");
            assertEquals(0xFFFF0000, buf.getPixel(0, 2), "Pixel should have moved up 1 row");
        }

        @Test
        void fillColumn() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(8, 8);
            buf.fillColumn(3, 0.5, 0xFFFF0000);

            // Bottom half should be filled
            assertEquals(0xFFFF0000, buf.getPixel(3, 7));
            assertEquals(0xFFFF0000, buf.getPixel(3, 4));
            // Top half should be empty
            assertEquals(0, buf.getPixel(3, 0));
        }
    }
}
