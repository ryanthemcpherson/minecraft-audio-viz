package com.audioviz.decorators;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BannerConfig - pure data class with JSON parsing and clamping.
 */
class BannerConfigTest {

    @Nested
    @DisplayName("empty() sentinel")
    class EmptySentinel {

        @Test
        @DisplayName("returns text mode by default")
        void defaultTextMode() {
            BannerConfig config = BannerConfig.empty();
            assertEquals("text", config.getMode());
            assertTrue(config.isTextMode());
            assertFalse(config.isImageMode());
        }

        @Test
        @DisplayName("has default text style bold")
        void defaultTextStyle() {
            assertEquals("bold", BannerConfig.empty().getTextStyle());
        }

        @Test
        @DisplayName("has default color mode frequency")
        void defaultColorMode() {
            assertEquals("frequency", BannerConfig.empty().getTextColorMode());
        }

        @Test
        @DisplayName("has default fixed color f")
        void defaultFixedColor() {
            assertEquals("f", BannerConfig.empty().getTextFixedColor());
        }

        @Test
        @DisplayName("has default text format %s")
        void defaultTextFormat() {
            assertEquals("%s", BannerConfig.empty().getTextFormat());
        }

        @Test
        @DisplayName("has default grid dimensions")
        void defaultGrid() {
            assertEquals(24, BannerConfig.empty().getGridWidth());
            assertEquals(12, BannerConfig.empty().getGridHeight());
        }

        @Test
        @DisplayName("has no image pixels")
        void noImagePixels() {
            assertFalse(BannerConfig.empty().hasImagePixels());
        }

        @Test
        @DisplayName("same instance on multiple calls")
        void sameInstance() {
            assertSame(BannerConfig.empty(), BannerConfig.empty());
        }
    }

    @Nested
    @DisplayName("Constructor Null Handling")
    class NullHandling {

        @Test
        @DisplayName("null mode defaults to text")
        void nullMode() {
            BannerConfig config = new BannerConfig(null, null, null, null, null, 24, 12, null);
            assertEquals("text", config.getMode());
        }

        @Test
        @DisplayName("null textStyle defaults to bold")
        void nullTextStyle() {
            BannerConfig config = new BannerConfig("text", null, null, null, null, 24, 12, null);
            assertEquals("bold", config.getTextStyle());
        }

        @Test
        @DisplayName("null textColorMode defaults to frequency")
        void nullColorMode() {
            BannerConfig config = new BannerConfig("text", "bold", null, null, null, 24, 12, null);
            assertEquals("frequency", config.getTextColorMode());
        }

        @Test
        @DisplayName("null textFixedColor defaults to f")
        void nullFixedColor() {
            BannerConfig config = new BannerConfig("text", "bold", "fixed", null, null, 24, 12, null);
            assertEquals("f", config.getTextFixedColor());
        }

        @Test
        @DisplayName("null textFormat defaults to %s")
        void nullTextFormat() {
            BannerConfig config = new BannerConfig("text", "bold", "frequency", "f", null, 24, 12, null);
            assertEquals("%s", config.getTextFormat());
        }
    }

    @Nested
    @DisplayName("Grid Dimension Clamping")
    class GridClamping {

        @Test
        @DisplayName("clamps width to minimum 4")
        void clampsWidthMin() {
            BannerConfig config = new BannerConfig("text", "bold", "frequency", "f", "%s", 1, 12, null);
            assertEquals(4, config.getGridWidth());
        }

        @Test
        @DisplayName("clamps width to maximum 48")
        void clampsWidthMax() {
            BannerConfig config = new BannerConfig("text", "bold", "frequency", "f", "%s", 100, 12, null);
            assertEquals(48, config.getGridWidth());
        }

        @Test
        @DisplayName("clamps height to minimum 2")
        void clampsHeightMin() {
            BannerConfig config = new BannerConfig("text", "bold", "frequency", "f", "%s", 24, 0, null);
            assertEquals(2, config.getGridHeight());
        }

        @Test
        @DisplayName("clamps height to maximum 24")
        void clampsHeightMax() {
            BannerConfig config = new BannerConfig("text", "bold", "frequency", "f", "%s", 24, 50, null);
            assertEquals(24, config.getGridHeight());
        }

        @Test
        @DisplayName("accepts valid dimensions")
        void acceptsValid() {
            BannerConfig config = new BannerConfig("text", "bold", "frequency", "f", "%s", 32, 16, null);
            assertEquals(32, config.getGridWidth());
            assertEquals(16, config.getGridHeight());
        }
    }

    @Nested
    @DisplayName("Mode Detection")
    class ModeDetection {

        @Test
        @DisplayName("isTextMode true for text")
        void textModeTrue() {
            BannerConfig config = new BannerConfig("text", "bold", "frequency", "f", "%s", 24, 12, null);
            assertTrue(config.isTextMode());
            assertFalse(config.isImageMode());
        }

        @Test
        @DisplayName("isImageMode true for image")
        void imageModeTrue() {
            BannerConfig config = new BannerConfig("image", "bold", "frequency", "f", "%s", 24, 12, null);
            assertTrue(config.isImageMode());
            assertFalse(config.isTextMode());
        }
    }

    @Nested
    @DisplayName("Image Pixels")
    class ImagePixels {

        @Test
        @DisplayName("hasImagePixels false when null")
        void falseWhenNull() {
            BannerConfig config = new BannerConfig("image", "bold", "frequency", "f", "%s", 24, 12, null);
            assertFalse(config.hasImagePixels());
        }

        @Test
        @DisplayName("hasImagePixels false when empty array")
        void falseWhenEmpty() {
            BannerConfig config = new BannerConfig("image", "bold", "frequency", "f", "%s", 24, 12, new int[0]);
            assertFalse(config.hasImagePixels());
        }

        @Test
        @DisplayName("hasImagePixels true when non-empty")
        void trueWhenNonEmpty() {
            BannerConfig config = new BannerConfig("image", "bold", "frequency", "f", "%s", 24, 12, new int[]{0xFF000000});
            assertTrue(config.hasImagePixels());
        }
    }

    @Nested
    @DisplayName("fromJson")
    class FromJson {

        @Test
        @DisplayName("parses all fields from JSON")
        void parsesAllFields() {
            JsonObject json = new JsonObject();
            json.addProperty("banner_mode", "image");
            json.addProperty("text_style", "neon");
            json.addProperty("text_color_mode", "rainbow");
            json.addProperty("text_fixed_color", "a");
            json.addProperty("text_format", "Now Playing: %s");
            json.addProperty("grid_width", 32);
            json.addProperty("grid_height", 16);

            BannerConfig config = BannerConfig.fromJson(json);
            assertEquals("image", config.getMode());
            assertEquals("neon", config.getTextStyle());
            assertEquals("rainbow", config.getTextColorMode());
            assertEquals("a", config.getTextFixedColor());
            assertEquals("Now Playing: %s", config.getTextFormat());
            assertEquals(32, config.getGridWidth());
            assertEquals(16, config.getGridHeight());
        }

        @Test
        @DisplayName("uses defaults for missing fields")
        void usesDefaults() {
            JsonObject json = new JsonObject();
            BannerConfig config = BannerConfig.fromJson(json);
            assertEquals("text", config.getMode());
            assertEquals("bold", config.getTextStyle());
            assertEquals("frequency", config.getTextColorMode());
            assertEquals("f", config.getTextFixedColor());
            assertEquals("%s", config.getTextFormat());
            assertEquals(24, config.getGridWidth());
            assertEquals(12, config.getGridHeight());
        }

        @Test
        @DisplayName("parses image_pixels array")
        void parsesImagePixels() {
            JsonObject json = new JsonObject();
            JsonArray pixels = new JsonArray();
            pixels.add(0xFF000000);
            pixels.add(0xFFFF0000);
            pixels.add(0xFF00FF00);
            json.add("image_pixels", pixels);

            BannerConfig config = BannerConfig.fromJson(json);
            assertTrue(config.hasImagePixels());
            int[] px = config.getImagePixels();
            assertEquals(3, px.length);
            assertEquals(0xFF000000, px[0]);
            assertEquals(0xFFFF0000, px[1]);
            assertEquals(0xFF00FF00, px[2]);
        }

        @Test
        @DisplayName("no image_pixels when field absent")
        void noPixelsWhenAbsent() {
            JsonObject json = new JsonObject();
            BannerConfig config = BannerConfig.fromJson(json);
            assertFalse(config.hasImagePixels());
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("includes key fields")
        void includesKeyFields() {
            BannerConfig config = new BannerConfig("text", "neon", "rainbow", "f", "%s", 24, 12, null);
            String str = config.toString();
            assertTrue(str.contains("text"));
            assertTrue(str.contains("neon"));
            assertTrue(str.contains("rainbow"));
            assertTrue(str.contains("24x12"));
        }
    }
}
