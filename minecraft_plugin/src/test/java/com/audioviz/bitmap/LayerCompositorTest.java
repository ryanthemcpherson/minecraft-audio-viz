package com.audioviz.bitmap;

import com.audioviz.bitmap.effects.LayerCompositor;
import com.audioviz.bitmap.effects.LayerCompositor.BlendMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LayerCompositor}.
 * Tests all blend modes, opacity control, and edge cases.
 */
class LayerCompositorTest {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;
    private static final int RED = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE = 0xFF0000FF;
    private static final int HALF_GRAY = 0xFF808080;

    private BitmapFrameBuffer solidBuffer(int w, int h, int color) {
        BitmapFrameBuffer buf = new BitmapFrameBuffer(w, h);
        buf.fill(color);
        return buf;
    }

    @Nested
    @DisplayName("NORMAL blend mode")
    class NormalBlend {
        @Test
        void fullOpacityReplacesBottom() {
            BitmapFrameBuffer bottom = solidBuffer(4, 4, RED);
            BitmapFrameBuffer top = solidBuffer(4, 4, BLUE);
            BitmapFrameBuffer output = new BitmapFrameBuffer(4, 4);

            LayerCompositor.blend(bottom, top, output, BlendMode.NORMAL, 1.0);
            assertEquals(BLUE, output.getPixel(0, 0));
        }

        @Test
        void zeroOpacityKeepsBottom() {
            BitmapFrameBuffer bottom = solidBuffer(4, 4, RED);
            BitmapFrameBuffer top = solidBuffer(4, 4, BLUE);
            BitmapFrameBuffer output = new BitmapFrameBuffer(4, 4);

            LayerCompositor.blend(bottom, top, output, BlendMode.NORMAL, 0.0);
            assertEquals(RED, output.getPixel(0, 0));
        }

        @Test
        void halfOpacityBlends() {
            BitmapFrameBuffer bottom = solidBuffer(4, 4, BLACK);
            BitmapFrameBuffer top = solidBuffer(4, 4, WHITE);
            BitmapFrameBuffer output = new BitmapFrameBuffer(4, 4);

            LayerCompositor.blend(bottom, top, output, BlendMode.NORMAL, 0.5);

            int result = output.getPixel(0, 0);
            int r = (result >> 16) & 0xFF;
            // Should be approximately 127-128
            assertTrue(r >= 126 && r <= 129, "Blended R channel: " + r);
        }
    }

    @Nested
    @DisplayName("ADDITIVE blend mode")
    class AdditiveBlend {
        @Test
        void addsChannels() {
            BitmapFrameBuffer bottom = solidBuffer(4, 4, RED);
            BitmapFrameBuffer top = solidBuffer(4, 4, GREEN);
            BitmapFrameBuffer output = new BitmapFrameBuffer(4, 4);

            LayerCompositor.blend(bottom, top, output, BlendMode.ADDITIVE, 1.0);

            int result = output.getPixel(0, 0);
            assertEquals(255, (result >> 16) & 0xFF, "Red channel preserved");
            assertEquals(255, (result >> 8) & 0xFF, "Green channel added");
        }

        @Test
        void clampsAt255() {
            BitmapFrameBuffer bottom = solidBuffer(4, 4, WHITE);
            BitmapFrameBuffer top = solidBuffer(4, 4, WHITE);
            BitmapFrameBuffer output = new BitmapFrameBuffer(4, 4);

            LayerCompositor.blend(bottom, top, output, BlendMode.ADDITIVE, 1.0);

            int result = output.getPixel(0, 0);
            assertEquals(255, (result >> 16) & 0xFF, "Should clamp to 255");
        }

        @Test
        void blackAddsNothing() {
            BitmapFrameBuffer bottom = solidBuffer(4, 4, RED);
            BitmapFrameBuffer top = solidBuffer(4, 4, BLACK);
            BitmapFrameBuffer output = new BitmapFrameBuffer(4, 4);

            LayerCompositor.blend(bottom, top, output, BlendMode.ADDITIVE, 1.0);
            assertEquals(RED, output.getPixel(0, 0));
        }
    }

    @Nested
    @DisplayName("MULTIPLY blend mode")
    class MultiplyBlend {
        @Test
        void whitePreservesBottom() {
            BitmapFrameBuffer bottom = solidBuffer(4, 4, RED);
            BitmapFrameBuffer top = solidBuffer(4, 4, WHITE);
            BitmapFrameBuffer output = new BitmapFrameBuffer(4, 4);

            LayerCompositor.blend(bottom, top, output, BlendMode.MULTIPLY, 1.0);
            assertEquals(RED, output.getPixel(0, 0));
        }

        @Test
        void blackZerosAll() {
            BitmapFrameBuffer bottom = solidBuffer(4, 4, WHITE);
            BitmapFrameBuffer top = solidBuffer(4, 4, BLACK);
            BitmapFrameBuffer output = new BitmapFrameBuffer(4, 4);

            LayerCompositor.blend(bottom, top, output, BlendMode.MULTIPLY, 1.0);

            int result = output.getPixel(0, 0);
            assertEquals(0, (result >> 16) & 0xFF, "R*0 = 0");
            assertEquals(0, (result >> 8) & 0xFF, "G*0 = 0");
            assertEquals(0, result & 0xFF, "B*0 = 0");
        }

        @Test
        void halfGrayHalvesValues() {
            BitmapFrameBuffer bottom = solidBuffer(4, 4, WHITE);
            BitmapFrameBuffer top = solidBuffer(4, 4, HALF_GRAY);
            BitmapFrameBuffer output = new BitmapFrameBuffer(4, 4);

            LayerCompositor.blend(bottom, top, output, BlendMode.MULTIPLY, 1.0);

            int result = output.getPixel(0, 0);
            int r = (result >> 16) & 0xFF;
            // 255 * 128 / 255 ≈ 128
            assertTrue(r >= 126 && r <= 130, "Multiply with 0.5 gray: " + r);
        }
    }

    @Nested
    @DisplayName("SCREEN blend mode")
    class ScreenBlend {
        @Test
        void blackPreservesBottom() {
            BitmapFrameBuffer bottom = solidBuffer(4, 4, RED);
            BitmapFrameBuffer top = solidBuffer(4, 4, BLACK);
            BitmapFrameBuffer output = new BitmapFrameBuffer(4, 4);

            LayerCompositor.blend(bottom, top, output, BlendMode.SCREEN, 1.0);
            assertEquals(RED, output.getPixel(0, 0));
        }

        @Test
        void whiteOverWhiteStaysWhite() {
            BitmapFrameBuffer bottom = solidBuffer(4, 4, WHITE);
            BitmapFrameBuffer top = solidBuffer(4, 4, WHITE);
            BitmapFrameBuffer output = new BitmapFrameBuffer(4, 4);

            LayerCompositor.blend(bottom, top, output, BlendMode.SCREEN, 1.0);
            assertEquals(WHITE, output.getPixel(0, 0));
        }

        @Test
        void screenBrightens() {
            BitmapFrameBuffer bottom = solidBuffer(4, 4, HALF_GRAY);
            BitmapFrameBuffer top = solidBuffer(4, 4, HALF_GRAY);
            BitmapFrameBuffer output = new BitmapFrameBuffer(4, 4);

            LayerCompositor.blend(bottom, top, output, BlendMode.SCREEN, 1.0);

            int result = output.getPixel(0, 0);
            int r = (result >> 16) & 0xFF;
            // screen(128, 128) = 255 - (127*127)/255 ≈ 192
            assertTrue(r > 128, "Screen should brighten, got " + r);
        }
    }

    @Nested
    @DisplayName("LIGHTEN and DARKEN")
    class LightenDarken {
        @Test
        void lightenKeepsMaxPerChannel() {
            BitmapFrameBuffer bottom = solidBuffer(4, 4, RED);       // R=255,G=0,B=0
            BitmapFrameBuffer top = solidBuffer(4, 4, GREEN);       // R=0,G=255,B=0
            BitmapFrameBuffer output = new BitmapFrameBuffer(4, 4);

            LayerCompositor.blend(bottom, top, output, BlendMode.LIGHTEN, 1.0);

            int result = output.getPixel(0, 0);
            assertEquals(255, (result >> 16) & 0xFF, "Max R");
            assertEquals(255, (result >> 8) & 0xFF, "Max G");
        }

        @Test
        void darkenKeepsMinPerChannel() {
            BitmapFrameBuffer bottom = solidBuffer(4, 4, WHITE);
            BitmapFrameBuffer top = solidBuffer(4, 4, RED);
            BitmapFrameBuffer output = new BitmapFrameBuffer(4, 4);

            LayerCompositor.blend(bottom, top, output, BlendMode.DARKEN, 1.0);

            int result = output.getPixel(0, 0);
            assertEquals(255, (result >> 16) & 0xFF, "R stays 255");
            assertEquals(0, (result >> 8) & 0xFF, "G darkens to 0");
            assertEquals(0, result & 0xFF, "B darkens to 0");
        }
    }

    @Nested
    @DisplayName("blendInPlace")
    class BlendInPlace {
        @Test
        void modifiesBottomDirectly() {
            BitmapFrameBuffer bottom = solidBuffer(4, 4, RED);
            BitmapFrameBuffer top = solidBuffer(4, 4, BLUE);

            LayerCompositor.blendInPlace(bottom, top, BlendMode.NORMAL, 1.0);

            assertEquals(BLUE, bottom.getPixel(0, 0), "Bottom should be overwritten");
        }
    }
}
