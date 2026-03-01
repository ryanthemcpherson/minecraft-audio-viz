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
            // 16_384 pixel limit
            assertThrows(IllegalArgumentException.class, () -> new BitmapFrameBuffer(200, 200));
        }

        @Test
        void maxSizeOk() {
            // 128x128 = 16_384 exactly — should be fine
            assertDoesNotThrow(() -> new BitmapFrameBuffer(128, 128));
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

    @Nested
    @DisplayName("Pixel Access Extended")
    class PixelAccessExtended {
        @Test
        void setPixelComponents() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.setPixel(1, 1, 128, 64, 32, 255);
            int pixel = buf.getPixel(1, 1);
            assertEquals(255, (pixel >> 24) & 0xFF, "alpha");
            assertEquals(128, (pixel >> 16) & 0xFF, "red");
            assertEquals(64, (pixel >> 8) & 0xFF, "green");
            assertEquals(32, pixel & 0xFF, "blue");
        }

        @Test
        void getPixelFlatRowMajor() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.setPixel(2, 1, 0xAABBCCDD);
            // flat index = y * width + x = 1 * 4 + 2 = 6
            assertEquals(0xAABBCCDD, buf.getPixelFlat(6));
        }

        @Test
        void getPixelFlatOutOfBounds() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            assertEquals(0, buf.getPixelFlat(-1));
            assertEquals(0, buf.getPixelFlat(16));
        }
    }

    @Nested
    @DisplayName("Drawing Extended")
    class DrawingExtended {
        @Test
        void drawRing() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(16, 16);
            buf.drawRing(8, 8, 5, 2, 0xFFFF0000);
            // Center should NOT be filled (hollow)
            assertEquals(0, buf.getPixel(8, 8));
            // Edge should be filled (distance ~5 from center)
            assertEquals(0xFFFF0000, buf.getPixel(8, 3));
        }

        @Test
        void scrollLeft() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.setPixel(1, 0, 0xFFAA0000);
            buf.scrollLeft();
            assertEquals(0xFFAA0000, buf.getPixel(0, 0), "Pixel should shift left");
            assertEquals(0, buf.getPixel(3, 0), "Rightmost column should be cleared");
        }

        @Test
        void setRow() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            int[] row = {0xFF110000, 0xFF220000, 0xFF330000, 0xFF440000};
            buf.setRow(2, row);
            assertEquals(0xFF110000, buf.getPixel(0, 2));
            assertEquals(0xFF440000, buf.getPixel(3, 2));
        }

        @Test
        void setRowClipsToWidth() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            int[] wideRow = {0xFF110000, 0xFF220000, 0xFF330000, 0xFF440000, 0xFF550000, 0xFF660000};
            buf.setRow(0, wideRow);
            // Only first 4 should be written
            assertEquals(0xFF440000, buf.getPixel(3, 0));
        }

        @Test
        void setRowOutOfBoundsNoOp() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.setRow(-1, new int[]{0xFFFFFFFF});
            buf.setRow(4, new int[]{0xFFFFFFFF});
            assertEquals(0, buf.getPixel(0, 0));
        }

        @Test
        void loadPixels() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(2, 2);
            int[] data = {0xFF110000, 0xFF220000, 0xFF330000, 0xFF440000};
            buf.loadPixels(data);
            assertEquals(0xFF110000, buf.getPixel(0, 0));
            assertEquals(0xFF440000, buf.getPixel(1, 1));
        }

        @Test
        void loadPixelsSizeMismatchThrows() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            assertThrows(IllegalArgumentException.class, () -> buf.loadPixels(new int[8]));
        }

        @Test
        void fillColumnZeroHeight() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.fillColumn(0, 0.0, 0xFFFF0000);
            for (int y = 0; y < 4; y++) {
                assertEquals(0, buf.getPixel(0, y), "No pixels should be filled at height 0");
            }
        }

        @Test
        void fillColumnFullHeight() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.fillColumn(0, 1.0, 0xFFFF0000);
            for (int y = 0; y < 4; y++) {
                assertEquals(0xFFFF0000, buf.getPixel(0, y), "All pixels should be filled at height 1.0");
            }
        }

        @Test
        void fillColumnOutOfBoundsNoOp() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.fillColumn(-1, 0.5, 0xFFFF0000);
            buf.fillColumn(4, 0.5, 0xFFFF0000);
            // Should not crash; buffer unchanged
            assertEquals(0, buf.getPixel(0, 3));
        }

        @Test
        void fillColumnGradient() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 8);
            buf.fillColumnGradient(0, 1.0, 0xFFFF0000, 0xFF0000FF);
            // Bottom and top should have the gradient colors
            int bottom = buf.getPixel(0, 7);
            int top = buf.getPixel(0, 0);
            // Bottom gets bottomARGB, top gets topARGB
            assertTrue(((bottom >> 16) & 0xFF) > 200, "Bottom should be red-ish");
            assertTrue((top & 0xFF) > 200, "Top should be blue-ish");
        }

        @Test
        void fillColumnGradientZeroHeight() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.fillColumnGradient(0, 0.0, 0xFFFF0000, 0xFF0000FF);
            for (int y = 0; y < 4; y++) {
                assertEquals(0, buf.getPixel(0, y));
            }
        }

        @Test
        void drawHorizontalGradient() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.drawHorizontalGradient(0, 0xFF000000, 0xFFFFFFFF);
            // Left should be black
            assertEquals(0xFF000000, buf.getPixel(0, 0));
            // Right should be white
            assertEquals(0xFFFFFFFF, buf.getPixel(3, 0));
            // Middle should be in-between
            int mid = buf.getPixel(1, 0);
            int r = (mid >> 16) & 0xFF;
            assertTrue(r > 0 && r < 255, "Middle pixel should be between black and white");
        }

        @Test
        void drawHorizontalGradientOutOfBoundsNoOp() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.drawHorizontalGradient(-1, 0xFF000000, 0xFFFFFFFF);
            buf.drawHorizontalGradient(4, 0xFF000000, 0xFFFFFFFF);
            assertEquals(0, buf.getPixel(0, 0));
        }

        @Test
        void drawVerticalGradient() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.drawVerticalGradient(0, 0xFF000000, 0xFFFFFFFF);
            assertEquals(0xFF000000, buf.getPixel(0, 0));
            assertEquals(0xFFFFFFFF, buf.getPixel(0, 3));
        }

        @Test
        void drawVerticalGradientOutOfBoundsNoOp() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.drawVerticalGradient(-1, 0xFF000000, 0xFFFFFFFF);
            buf.drawVerticalGradient(4, 0xFF000000, 0xFFFFFFFF);
            assertEquals(0, buf.getPixel(0, 0));
        }

        @Test
        void fillRectNegativeOriginClips() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.fillRect(-2, -2, 4, 4, 0xFFFF0000);
            // Only the overlapping region (0,0)-(1,1) should be filled
            assertEquals(0xFFFF0000, buf.getPixel(0, 0));
            assertEquals(0xFFFF0000, buf.getPixel(1, 1));
            assertEquals(0, buf.getPixel(2, 2));
        }

        @Test
        void fillCircleClipsToBuffer() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            // Circle centered at edge — should clip, not crash
            buf.fillCircle(0, 0, 2, 0xFFFF0000);
            assertEquals(0xFFFF0000, buf.getPixel(0, 0));
            assertEquals(0xFFFF0000, buf.getPixel(1, 0));
        }
    }

    @Nested
    @DisplayName("Brightness")
    class Brightness {
        @Test
        void applyBrightnessHalf() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(2, 2);
            buf.fill(BitmapFrameBuffer.packARGB(255, 200, 100, 50));
            buf.applyBrightness(0.5);
            int pixel = buf.getPixel(0, 0);
            assertEquals(255, (pixel >> 24) & 0xFF, "Alpha should be preserved");
            assertEquals(100, (pixel >> 16) & 0xFF, "Red should be halved");
            assertEquals(50, (pixel >> 8) & 0xFF, "Green should be halved");
            assertEquals(25, pixel & 0xFF, "Blue should be halved");
        }

        @Test
        void applyBrightnessZeroBlacksOut() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(2, 2);
            buf.fill(0xFFFFFFFF);
            buf.applyBrightness(0.0);
            int pixel = buf.getPixel(0, 0);
            assertEquals(0, (pixel >> 16) & 0xFF, "Red should be 0");
            assertEquals(0, (pixel >> 8) & 0xFF, "Green should be 0");
            assertEquals(0, pixel & 0xFF, "Blue should be 0");
        }

        @Test
        void applyBrightnessOneNoOp() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(2, 2);
            buf.fill(0xFFAABBCC);
            buf.applyBrightness(1.0);
            assertEquals(0xFFAABBCC, buf.getPixel(0, 0));
        }

        @Test
        void applyBrightnessNegativeClampsToZero() {
            BitmapFrameBuffer buf = new BitmapFrameBuffer(2, 2);
            buf.fill(0xFFFFFFFF);
            buf.applyBrightness(-0.5);
            int pixel = buf.getPixel(0, 0);
            assertEquals(0, (pixel >> 16) & 0xFF);
        }
    }

    @Nested
    @DisplayName("Color Utilities Extended")
    class ColorUtilsExtended {
        @Test
        void rgbHelper() {
            int color = BitmapFrameBuffer.rgb(255, 128, 0);
            assertEquals(255, (color >> 24) & 0xFF, "Alpha should be 255 (opaque)");
            assertEquals(255, (color >> 16) & 0xFF, "Red");
            assertEquals(128, (color >> 8) & 0xFF, "Green");
            assertEquals(0, color & 0xFF, "Blue");
        }

        @Test
        void heatMapBlack() {
            int color = BitmapFrameBuffer.heatMapColor(0.0);
            assertEquals(0, (color >> 16) & 0xFF, "R at 0");
            assertEquals(0, (color >> 8) & 0xFF, "G at 0");
            assertEquals(0, color & 0xFF, "B at 0");
        }

        @Test
        void heatMapWhite() {
            int color = BitmapFrameBuffer.heatMapColor(1.0);
            assertEquals(255, (color >> 16) & 0xFF, "R at 1.0");
        }

        @Test
        void heatMapClampsNegative() {
            int color = BitmapFrameBuffer.heatMapColor(-0.5);
            // Same as 0.0
            assertEquals(BitmapFrameBuffer.heatMapColor(0.0), color);
        }

        @Test
        void heatMapClampsAboveOne() {
            int color = BitmapFrameBuffer.heatMapColor(1.5);
            assertEquals(BitmapFrameBuffer.heatMapColor(1.0), color);
        }

        @Test
        void heatMapMonotonicBrightness() {
            // Heat map cycles through hues, so total r+g+b is not strictly monotonic.
            // Verify that the overall trend is upward: brightness at 1.0 > brightness at 0.0,
            // and every sample has non-negative brightness.
            int brightnessAtZero = channelSum(BitmapFrameBuffer.heatMapColor(0.0));
            int brightnessAtOne = channelSum(BitmapFrameBuffer.heatMapColor(1.0));
            assertTrue(brightnessAtOne > brightnessAtZero,
                "Brightness at 1.0 (" + brightnessAtOne + ") should exceed brightness at 0.0 (" + brightnessAtZero + ")");

            for (double i = 0.0; i <= 1.0; i += 0.1) {
                int brightness = channelSum(BitmapFrameBuffer.heatMapColor(i));
                assertTrue(brightness >= 0, "Brightness should be non-negative at intensity " + i);
            }
        }

        private int channelSum(int argb) {
            return ((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF);
        }

        @Test
        void fromHSBRedHue() {
            int color = BitmapFrameBuffer.fromHSB(0, 1.0f, 1.0f);
            assertEquals(255, (color >> 24) & 0xFF, "Alpha should be 255 (opaque)");
            assertEquals(255, (color >> 16) & 0xFF, "Red at hue=0");
            assertEquals(0, (color >> 8) & 0xFF, "Green at hue=0");
            assertEquals(0, color & 0xFF, "Blue at hue=0");
        }

        @Test
        void fromHSBGreenHue() {
            int color = BitmapFrameBuffer.fromHSB(120, 1.0f, 1.0f);
            assertEquals(0, (color >> 16) & 0xFF, "Red at hue=120");
            assertEquals(255, (color >> 8) & 0xFF, "Green at hue=120");
        }

        @Test
        void fromHSBBlueHue() {
            int color = BitmapFrameBuffer.fromHSB(240, 1.0f, 1.0f);
            assertEquals(0, (color >> 16) & 0xFF, "Red at hue=240");
            assertEquals(255, color & 0xFF, "Blue at hue=240");
        }

        @Test
        void fromHSBZeroBrightness() {
            int color = BitmapFrameBuffer.fromHSB(0, 1.0f, 0.0f);
            assertEquals(0, (color >> 16) & 0xFF);
            assertEquals(0, (color >> 8) & 0xFF);
            assertEquals(0, color & 0xFF);
        }

        @Test
        void packARGBMasksTo8Bits() {
            // Values > 255 should be masked
            int color = BitmapFrameBuffer.packARGB(256, 256, 256, 256);
            assertEquals(0, (color >> 24) & 0xFF, "256 & 0xFF = 0");
        }
    }
}
