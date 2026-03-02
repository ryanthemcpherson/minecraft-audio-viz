# Java Plugin Unit Test Expansion — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Expand unit test coverage for the Minecraft plugin's pure-logic classes, creating a safety net for refactoring.

**Architecture:** Add ~150-200 test methods across 1 new + 7 expanded test files, targeting algorithmic edge cases (NaN, boundary values, overflow, empty inputs). All tests are pure unit tests against existing production code — no production changes needed.

**Tech Stack:** JUnit 5.12.1, Mockito 5.21 (minimal — only for Bukkit `Color`), Maven Surefire

**Test command:** `cd minecraft_plugin && mvn test -pl . 2>&1 | tail -20`

**Conventions:** `@Nested` + `@DisplayName` groups, `@ParameterizedTest` for boundaries, helper factories per test class.

**Package:** `com.audioviz` (NOT `com.ryanalexander.audioviz`)

---

### Task 1: BeatProjectionUtil — New Test Class

**Files:**
- Create: `minecraft_plugin/src/test/java/com/audioviz/protocol/BeatProjectionUtilTest.java`

**Step 1: Write BeatProjectionUtilTest.java**

```java
package com.audioviz.protocol;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BeatProjectionUtilTest {

    private Map<String, Long> cooldownMap;

    @BeforeEach
    void setUp() {
        cooldownMap = new HashMap<>();
    }

    @Nested
    @DisplayName("Explicit Beat Pass-Through")
    class ExplicitBeat {

        @Test
        @DisplayName("explicit beat returns true with clamped intensity")
        void explicitBeatReturnsTrue() {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", true, 0.75, 120, 0.9, 0.5, cooldownMap);
            assertTrue(result.isBeat());
            assertEquals(0.75, result.beatIntensity(), 0.001);
        }

        @Test
        @DisplayName("explicit beat clamps intensity > 1 to 1")
        void clampsHighIntensity() {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", true, 1.5, 120, 0.9, 0.5, cooldownMap);
            assertTrue(result.isBeat());
            assertEquals(1.0, result.beatIntensity(), 0.001);
        }

        @Test
        @DisplayName("explicit beat clamps negative intensity to 0")
        void clampsNegativeIntensity() {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", true, -0.5, 120, 0.9, 0.5, cooldownMap);
            assertTrue(result.isBeat());
            assertEquals(0.0, result.beatIntensity(), 0.001);
        }

        @Test
        @DisplayName("explicit beat clamps NaN intensity to 0")
        void clampsNanIntensity() {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", true, Double.NaN, 120, 0.9, 0.5, cooldownMap);
            assertTrue(result.isBeat());
            assertEquals(0.0, result.beatIntensity(), 0.001);
        }

        @Test
        @DisplayName("explicit beat updates cooldown map")
        void updatesCooldownMap() {
            BeatProjectionUtil.projectBeat(
                "zone1", true, 0.8, 120, 0.9, 0.5, cooldownMap);
            assertTrue(cooldownMap.containsKey("zone1"));
            assertTrue(cooldownMap.get("zone1") > 0);
        }
    }

    @Nested
    @DisplayName("Confidence and BPM Thresholds")
    class Thresholds {

        @ParameterizedTest
        @ValueSource(doubles = {0.0, 0.1, 0.3, 0.59})
        @DisplayName("low tempo confidence rejects synthesis")
        void lowConfidenceRejects(double confidence) {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, confidence, 0.05, cooldownMap);
            assertFalse(result.isBeat());
            assertEquals(0.0, result.beatIntensity());
        }

        @Test
        @DisplayName("confidence at threshold allows synthesis")
        void confidenceAtThreshold() {
            // 0.60 is the threshold — should pass confidence check
            // Phase 0.05 is within edge window, no cooldown
            var result = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 0.60, 0.05, cooldownMap);
            assertTrue(result.isBeat());
        }

        @ParameterizedTest
        @ValueSource(doubles = {0.0, 30.0, 59.9})
        @DisplayName("low BPM rejects synthesis")
        void lowBpmRejects(double bpm) {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, bpm, 0.9, 0.05, cooldownMap);
            assertFalse(result.isBeat());
        }

        @Test
        @DisplayName("BPM at threshold allows synthesis")
        void bpmAtThreshold() {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 60.0, 0.9, 0.05, cooldownMap);
            assertTrue(result.isBeat());
        }
    }

    @Nested
    @DisplayName("Phase Edge Detection")
    class PhaseEdge {

        @ParameterizedTest
        @CsvSource({"0.0", "0.05", "0.12"})
        @DisplayName("phase near 0 (within edge window) triggers")
        void phaseNearZero(double phase) {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 0.9, phase, cooldownMap);
            assertTrue(result.isBeat(), "Phase " + phase + " should be within edge window");
        }

        @ParameterizedTest
        @CsvSource({"0.88", "0.95", "1.0"})
        @DisplayName("phase near 1 (within edge window) triggers")
        void phaseNearOne(double phase) {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 0.9, phase, cooldownMap);
            assertTrue(result.isBeat(), "Phase " + phase + " should be within edge window");
        }

        @ParameterizedTest
        @CsvSource({"0.13", "0.3", "0.5", "0.7, 0.87"})
        @DisplayName("phase in middle rejects synthesis")
        void phaseMiddle(double phase) {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 0.9, phase, cooldownMap);
            assertFalse(result.isBeat(), "Phase " + phase + " should NOT be in edge window");
        }
    }

    @Nested
    @DisplayName("Cooldown Tracking")
    class Cooldown {

        @Test
        @DisplayName("second synth beat within cooldown is rejected")
        void cooldownRejects() {
            // First call succeeds
            var first = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 0.9, 0.05, cooldownMap);
            assertTrue(first.isBeat());

            // Immediate second call — within cooldown
            var second = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 0.9, 0.05, cooldownMap);
            assertFalse(second.isBeat());
        }

        @Test
        @DisplayName("different zones have independent cooldowns")
        void independentZones() {
            var z1 = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 0.9, 0.05, cooldownMap);
            var z2 = BeatProjectionUtil.projectBeat(
                "zone2", false, 0.0, 120, 0.9, 0.05, cooldownMap);
            assertTrue(z1.isBeat());
            assertTrue(z2.isBeat());
        }

        @Test
        @DisplayName("explicit beat resets cooldown for zone")
        void explicitResetsCooldown() {
            // Synthesize a beat (sets cooldown)
            BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 0.9, 0.05, cooldownMap);
            // Explicit beat overrides cooldown
            var result = BeatProjectionUtil.projectBeat(
                "zone1", true, 0.8, 120, 0.9, 0.5, cooldownMap);
            assertTrue(result.isBeat());
        }
    }

    @Nested
    @DisplayName("Synthesized Beat Intensity")
    class SynthIntensity {

        @Test
        @DisplayName("synth intensity is at least SYNTH_BEAT_MIN_INTENSITY")
        void minimumIntensity() {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 0.60, 0.05, cooldownMap);
            assertTrue(result.isBeat());
            assertTrue(result.beatIntensity() >= BeatProjectionUtil.SYNTH_BEAT_MIN_INTENSITY);
        }

        @Test
        @DisplayName("synth intensity scales with tempo confidence")
        void scalesWithConfidence() {
            var low = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 0.65, 0.05, cooldownMap);
            cooldownMap.clear();
            var high = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 1.0, 0.05, cooldownMap);
            assertTrue(high.beatIntensity() >= low.beatIntensity());
        }

        @Test
        @DisplayName("synth intensity clamped to [0, 1]")
        void clampedToRange() {
            var result = BeatProjectionUtil.projectBeat(
                "zone1", false, 0.0, 120, 1.0, 0.05, cooldownMap);
            assertTrue(result.beatIntensity() >= 0.0);
            assertTrue(result.beatIntensity() <= 1.0);
        }
    }

    @Nested
    @DisplayName("Constants")
    class Constants {

        @Test
        @DisplayName("constants have expected values")
        void expectedValues() {
            assertEquals(0.60, BeatProjectionUtil.MIN_PHASE_ASSIST_CONFIDENCE);
            assertEquals(60.0, BeatProjectionUtil.MIN_PHASE_ASSIST_BPM);
            assertEquals(0.12, BeatProjectionUtil.PHASE_EDGE_WINDOW);
            assertEquals(0.25, BeatProjectionUtil.SYNTH_BEAT_MIN_INTENSITY);
            assertEquals(0.60, BeatProjectionUtil.SYNTH_BEAT_COOLDOWN_FRACTION);
            assertEquals(120L, BeatProjectionUtil.SYNTH_BEAT_COOLDOWN_MIN_MS);
        }
    }
}
```

**Step 2: Run tests**

Run: `cd minecraft_plugin && mvn test -Dtest=com.audioviz.protocol.BeatProjectionUtilTest -pl . 2>&1 | tail -30`
Expected: All tests PASS (testing existing production code)

**Step 3: Commit**

```bash
git add minecraft_plugin/src/test/java/com/audioviz/protocol/BeatProjectionUtilTest.java
git commit -m "test: add BeatProjectionUtil unit tests — beat synthesis, thresholds, cooldowns"
```

---

### Task 2: BitmapFrameBuffer — Expand Tests

**Files:**
- Modify: `minecraft_plugin/src/test/java/com/audioviz/bitmap/BitmapFrameBufferTest.java`

**Step 1: Add new test groups to BitmapFrameBufferTest.java**

Add these `@Nested` classes inside the existing `BitmapFrameBufferTest` class, after the existing `Drawing` class:

```java
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
            // As intensity increases, total brightness (r+g+b) should generally increase
            int prev = 0;
            for (double i = 0.0; i <= 1.0; i += 0.2) {
                int color = BitmapFrameBuffer.heatMapColor(i);
                int r = (color >> 16) & 0xFF;
                int g = (color >> 8) & 0xFF;
                int b = color & 0xFF;
                int brightness = r + g + b;
                assertTrue(brightness >= prev,
                    "Brightness should increase: " + brightness + " < " + prev + " at intensity " + i);
                prev = brightness;
            }
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
```

**Step 2: Run tests**

Run: `cd minecraft_plugin && mvn test -Dtest=com.audioviz.bitmap.BitmapFrameBufferTest -pl . 2>&1 | tail -30`
Expected: All tests PASS

**Step 3: Commit**

```bash
git add minecraft_plugin/src/test/java/com/audioviz/bitmap/BitmapFrameBufferTest.java
git commit -m "test: expand BitmapFrameBuffer tests — gradients, scroll, brightness, heatmap, ring"
```

---

### Task 3: ColorPalette — Expand Tests

**Files:**
- Modify: `minecraft_plugin/src/test/java/com/audioviz/bitmap/ColorPaletteTest.java`

**Step 1: Add new test groups to ColorPaletteTest.java**

Add these `@Nested` classes inside the existing test class:

```java
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
```

**Step 2: Run tests**

Run: `cd minecraft_plugin && mvn test -Dtest=com.audioviz.bitmap.ColorPaletteTest -pl . 2>&1 | tail -30`
Expected: All tests PASS

**Step 3: Commit**

```bash
git add minecraft_plugin/src/test/java/com/audioviz/bitmap/ColorPaletteTest.java
git commit -m "test: expand ColorPalette tests — NaN/Inf, smooth mapping, gradient edge cases"
```

---

### Task 4: CellGridMerger — Expand Tests

**Files:**
- Modify: `minecraft_plugin/src/test/java/com/audioviz/bitmap/adaptive/CellGridMergerTest.java`

**Step 1: Add new test groups to CellGridMergerTest.java**

```java
    @Nested
    @DisplayName("Cell Grid Height Calculation")
    class CellGridHeight {
        @Test
        void evenHeight() {
            assertEquals(4, CellGridMerger.cellGridHeight(8));
        }

        @Test
        void oddHeight() {
            assertEquals(5, CellGridMerger.cellGridHeight(9));
        }

        @Test
        void heightOne() {
            assertEquals(1, CellGridMerger.cellGridHeight(1));
        }

        @Test
        void heightTwo() {
            assertEquals(1, CellGridMerger.cellGridHeight(2));
        }

        @Test
        void heightZero() {
            // (0+1)/2 = 0 in integer division
            assertEquals(0, CellGridMerger.cellGridHeight(0));
        }
    }

    @Nested
    @DisplayName("Build Cell Grid")
    class BuildCellGrid {
        @Test
        void oddHeightBottomRowTransparent() {
            // 2x3 pixels: the bottom cell row has only 1 pixel row
            int[] pixels = {
                0xFFAA0000, 0xFFBB0000,  // row 0
                0xFFCC0000, 0xFFDD0000,  // row 1
                0xFFEE0000, 0xFFFF0000,  // row 2
            };
            HalfBlockCell[] cells = CellGridMerger.buildCellGrid(pixels, 2, 3);
            // cellGridHeight(3) = 2, so 2x2 = 4 cells
            assertEquals(4, cells.length);
            // Second cell row: top=row2, bottom=transparent (0)
            assertEquals(0xFFEE0000, cells[2].topARGB());
            assertEquals(0, cells[2].bottomARGB());
        }

        @Test
        void evenHeightAllPaired() {
            int[] pixels = {
                0xFF110000, 0xFF220000,  // row 0
                0xFF330000, 0xFF440000,  // row 1
            };
            HalfBlockCell[] cells = CellGridMerger.buildCellGrid(pixels, 2, 2);
            // cellGridHeight(2) = 1, so 2x1 = 2 cells
            assertEquals(2, cells.length);
            assertEquals(0xFF110000, cells[0].topARGB());
            assertEquals(0xFF330000, cells[0].bottomARGB());
        }
    }

    @Nested
    @DisplayName("Merge Coverage Invariant")
    class MergeCoverage {
        @Test
        void mergeCoversTotalCellCount() {
            // 4x4 pixels, mixed colors
            int[] pixels = new int[16];
            for (int i = 0; i < 16; i++) pixels[i] = 0xFF000000 + i;
            var rects = CellGridMerger.merge(pixels, 4, 4);
            int totalCells = rects.stream().mapToInt(MergedRect::cellCount).sum();
            assertEquals(4 * CellGridMerger.cellGridHeight(4), totalCells);
        }

        @Test
        void asCellRectsMatchesCellCount() {
            int[] pixels = new int[16];
            for (int i = 0; i < 16; i++) pixels[i] = 0xFF000000 + i;
            var rects = CellGridMerger.asCellRects(pixels, 4, 4);
            assertEquals(4 * CellGridMerger.cellGridHeight(4), rects.size());
            // All should be 1x1
            for (var r : rects) {
                assertEquals(1, r.w());
                assertEquals(1, r.h());
            }
        }
    }

    @Nested
    @DisplayName("Merge Patterns")
    class MergePatterns {
        @Test
        void checkerboardNoMerging() {
            // Alternating colors — nothing should merge
            int a = 0xFFFF0000, b = 0xFF00FF00;
            int[] pixels = {
                a, b, a, b,
                b, a, b, a,
                a, b, a, b,
                b, a, b, a,
            };
            var rects = CellGridMerger.merge(pixels, 4, 4);
            // Each cell will have different top/bottom, so no merging
            int cellCount = 4 * CellGridMerger.cellGridHeight(4);
            assertEquals(cellCount, rects.size(), "Checkerboard should produce 1 rect per cell");
        }

        @Test
        void horizontalStripeMergesAcross() {
            // All same color — two rows of pixels, both same → 1 cell row, all uniform
            int c = 0xFFAA0000;
            int[] pixels = {c, c, c, c, c, c, c, c}; // 4x2
            var rects = CellGridMerger.merge(pixels, 4, 2);
            assertEquals(1, rects.size(), "Solid 4x2 should merge to 1 rect");
            assertEquals(4, rects.get(0).w());
            assertEquals(1, rects.get(0).h());
        }

        @Test
        void verticalStripeMergesDown() {
            // 1 column, 4 rows, all same color
            int c = 0xFFBB0000;
            int[] pixels = {c, c, c, c}; // 1x4
            var rects = CellGridMerger.merge(pixels, 1, 4);
            assertEquals(1, rects.size(), "Single color column should merge to 1 rect");
        }
    }

    @Nested
    @DisplayName("BuildCellGridInto")
    class BuildCellGridInto {
        @Test
        void matchesBuildCellGrid() {
            int[] pixels = {
                0xFF110000, 0xFF220000,
                0xFF330000, 0xFF440000,
                0xFF550000, 0xFF660000,
            };
            HalfBlockCell[] cells = CellGridMerger.buildCellGrid(pixels, 2, 3);
            int cellCount = 2 * CellGridMerger.cellGridHeight(3);
            int[] topARGB = new int[cellCount];
            int[] bottomARGB = new int[cellCount];
            CellGridMerger.buildCellGridInto(pixels, 2, 3, topARGB, bottomARGB);

            for (int i = 0; i < cellCount; i++) {
                assertEquals(cells[i].topARGB(), topARGB[i], "Top mismatch at " + i);
                assertEquals(cells[i].bottomARGB(), bottomARGB[i], "Bottom mismatch at " + i);
            }
        }
    }
```

**Step 2: Run tests**

Run: `cd minecraft_plugin && mvn test -Dtest=com.audioviz.bitmap.adaptive.CellGridMergerTest -pl . 2>&1 | tail -30`
Expected: All tests PASS

**Step 3: Commit**

```bash
git add minecraft_plugin/src/test/java/com/audioviz/bitmap/adaptive/CellGridMergerTest.java
git commit -m "test: expand CellGridMerger tests — height calc, coverage invariant, merge patterns"
```

---

### Task 5: AdaptiveEntityAssigner — Expand Tests

**Files:**
- Modify: `minecraft_plugin/src/test/java/com/audioviz/bitmap/adaptive/AdaptiveEntityAssignerTest.java`

**Step 1: Add new test groups to AdaptiveEntityAssignerTest.java**

```java
    @Nested
    @DisplayName("Text Update Semantics")
    class TextUpdates {
        @Test
        void uniformCellGetsSentinelMinusOne() {
            var assigner = new AdaptiveEntityAssigner(4);
            List<MergedRect> rects = List.of(
                new MergedRect(0, 0, 1, 1, 0xFFFF0000, 0xFFFF0000) // uniform
            );
            var diff = assigner.assign(rects, 1.0f);
            // Uniform cells should get text update with argb = -1 (space sentinel)
            assertFalse(diff.textUpdates().isEmpty(), "First frame should have text update");
            assertEquals(-1, diff.textUpdates().get(0).argb(), "Uniform cell should use -1 sentinel");
        }

        @Test
        void nonUniformCellGetsBottomColor() {
            var assigner = new AdaptiveEntityAssigner(4);
            List<MergedRect> rects = List.of(
                new MergedRect(0, 0, 1, 1, 0xFFFF0000, 0xFF00FF00) // non-uniform
            );
            var diff = assigner.assign(rects, 1.0f);
            assertFalse(diff.textUpdates().isEmpty());
            assertEquals(0xFF00FF00, diff.textUpdates().get(0).argb());
        }

        @Test
        void uniformToNonUniformTriggersTextUpdate() {
            var assigner = new AdaptiveEntityAssigner(4);
            // Frame 1: uniform
            assigner.assign(List.of(
                new MergedRect(0, 0, 1, 1, 0xFFAA0000, 0xFFAA0000)
            ), 1.0f);
            // Frame 2: non-uniform (same position)
            var diff = assigner.assign(List.of(
                new MergedRect(0, 0, 1, 1, 0xFFAA0000, 0xFF00BB00)
            ), 1.0f);
            assertFalse(diff.textUpdates().isEmpty(), "Uniform→non-uniform should trigger text update");
            assertEquals(0xFF00BB00, diff.textUpdates().get(0).argb());
        }

        @Test
        void nonUniformToUniformTriggersTextUpdate() {
            var assigner = new AdaptiveEntityAssigner(4);
            // Frame 1: non-uniform
            assigner.assign(List.of(
                new MergedRect(0, 0, 1, 1, 0xFFAA0000, 0xFF00BB00)
            ), 1.0f);
            // Frame 2: uniform
            var diff = assigner.assign(List.of(
                new MergedRect(0, 0, 1, 1, 0xFFCC0000, 0xFFCC0000)
            ), 1.0f);
            assertFalse(diff.textUpdates().isEmpty(), "Non-uniform→uniform should trigger text update");
            assertEquals(-1, diff.textUpdates().get(0).argb(), "Should switch to space (-1)");
        }
    }

    @Nested
    @DisplayName("Geometry Dirty Detection")
    class GeometryDirty {
        @Test
        void positionChangeIsDirty() {
            var assigner = new AdaptiveEntityAssigner(4);
            assigner.assign(List.of(new MergedRect(0, 0, 1, 1, 0xFF000000, 0xFF000000)), 1.0f);
            var diff = assigner.assign(List.of(new MergedRect(1, 0, 1, 1, 0xFF000000, 0xFF000000)), 1.0f);
            assertFalse(diff.geometryUpdates().isEmpty(), "Position change should be dirty");
        }

        @Test
        void sizeChangeIsDirty() {
            var assigner = new AdaptiveEntityAssigner(4);
            assigner.assign(List.of(new MergedRect(0, 0, 1, 1, 0xFF000000, 0xFF000000)), 1.0f);
            var diff = assigner.assign(List.of(new MergedRect(0, 0, 2, 1, 0xFF000000, 0xFF000000)), 1.0f);
            assertFalse(diff.geometryUpdates().isEmpty(), "Size change should be dirty");
        }

        @Test
        void colorOnlyChangeNotGeoDirty() {
            var assigner = new AdaptiveEntityAssigner(4);
            assigner.assign(List.of(new MergedRect(0, 0, 1, 1, 0xFF000000, 0xFF000000)), 1.0f);
            var diff = assigner.assign(List.of(new MergedRect(0, 0, 1, 1, 0xFFFF0000, 0xFFFF0000)), 1.0f);
            assertTrue(diff.geometryUpdates().isEmpty(), "Color-only change should NOT be geo dirty");
            assertFalse(diff.backgroundUpdates().isEmpty(), "Color-only change should be bg dirty");
        }
    }

    @Nested
    @DisplayName("Hide Mechanics")
    class HideMechanics {
        @Test
        void reducingRectsHidesPrevious() {
            var assigner = new AdaptiveEntityAssigner(10);
            assigner.assign(List.of(
                new MergedRect(0, 0, 1, 1, 0xFFAA0000, 0xFFAA0000),
                new MergedRect(1, 0, 1, 1, 0xFFBB0000, 0xFFBB0000),
                new MergedRect(2, 0, 1, 1, 0xFFCC0000, 0xFFCC0000)
            ), 1.0f);
            var diff = assigner.assign(List.of(
                new MergedRect(0, 0, 1, 1, 0xFFAA0000, 0xFFAA0000)
            ), 1.0f);
            assertEquals(1, diff.hideStart());
            assertEquals(2, diff.hideCount(), "Should hide 2 previously active slots");
        }

        @Test
        void increasingRectsNoHide() {
            var assigner = new AdaptiveEntityAssigner(10);
            assigner.assign(List.of(
                new MergedRect(0, 0, 1, 1, 0xFFAA0000, 0xFFAA0000)
            ), 1.0f);
            var diff = assigner.assign(List.of(
                new MergedRect(0, 0, 1, 1, 0xFFAA0000, 0xFFAA0000),
                new MergedRect(1, 0, 1, 1, 0xFFBB0000, 0xFFBB0000)
            ), 1.0f);
            assertEquals(0, diff.hideCount());
        }
    }

    @Nested
    @DisplayName("Pixel Scale")
    class PixelScale {
        @Test
        void scaleAffectsGeometry() {
            var assigner = new AdaptiveEntityAssigner(4);
            var diff = assigner.assign(List.of(
                new MergedRect(2, 3, 1, 1, 0xFF000000, 0xFF000000)
            ), 0.5f);
            var geo = diff.geometryUpdates().get(0);
            assertEquals(1.0f, geo.x(), 0.001, "x = 2 * 0.5");
            assertEquals(1.5f, geo.y(), 0.001, "y = 3 * 0.5");
            assertEquals(0.5f, geo.scaleX(), 0.001);
            assertEquals(0.5f, geo.scaleY(), 0.001);
        }
    }
```

**Step 2: Run tests**

Run: `cd minecraft_plugin && mvn test -Dtest=com.audioviz.bitmap.adaptive.AdaptiveEntityAssignerTest -pl . 2>&1 | tail -30`
Expected: All tests PASS

**Step 3: Commit**

```bash
git add minecraft_plugin/src/test/java/com/audioviz/bitmap/adaptive/AdaptiveEntityAssignerTest.java
git commit -m "test: expand AdaptiveEntityAssigner tests — text semantics, dirty detection, hide mechanics"
```

---

### Task 6: LayerCompositor — Expand Tests

**Files:**
- Modify: `minecraft_plugin/src/test/java/com/audioviz/bitmap/LayerCompositorTest.java`

**Step 1: Add mathematical verification tests**

```java
    @Nested
    @DisplayName("Blend Mode Math")
    class BlendModeMath {
        @Test
        void additiveOverflowClamps() {
            BitmapFrameBuffer bottom = new BitmapFrameBuffer(1, 1);
            BitmapFrameBuffer top = new BitmapFrameBuffer(1, 1);
            BitmapFrameBuffer output = new BitmapFrameBuffer(1, 1);
            bottom.fill(BitmapFrameBuffer.packARGB(255, 200, 200, 200));
            top.fill(BitmapFrameBuffer.packARGB(255, 200, 200, 200));
            LayerCompositor.blend(bottom, top, output, LayerCompositor.BlendMode.ADDITIVE, 1.0);
            int pixel = output.getPixel(0, 0);
            // 200 + 200 = 400, clamped to 255
            assertEquals(255, (pixel >> 16) & 0xFF, "R clamped");
            assertEquals(255, (pixel >> 8) & 0xFF, "G clamped");
            assertEquals(255, pixel & 0xFF, "B clamped");
        }

        @Test
        void multiplyFormula() {
            BitmapFrameBuffer bottom = new BitmapFrameBuffer(1, 1);
            BitmapFrameBuffer top = new BitmapFrameBuffer(1, 1);
            BitmapFrameBuffer output = new BitmapFrameBuffer(1, 1);
            bottom.fill(BitmapFrameBuffer.packARGB(255, 100, 100, 100));
            top.fill(BitmapFrameBuffer.packARGB(255, 200, 200, 200));
            LayerCompositor.blend(bottom, top, output, LayerCompositor.BlendMode.MULTIPLY, 1.0);
            int pixel = output.getPixel(0, 0);
            // (100 * 200) / 255 = 78
            int expected = (100 * 200) / 255;
            assertEquals(expected, (pixel >> 16) & 0xFF, "R = (100*200)/255");
        }

        @Test
        void screenFormula() {
            BitmapFrameBuffer bottom = new BitmapFrameBuffer(1, 1);
            BitmapFrameBuffer top = new BitmapFrameBuffer(1, 1);
            BitmapFrameBuffer output = new BitmapFrameBuffer(1, 1);
            bottom.fill(BitmapFrameBuffer.packARGB(255, 100, 100, 100));
            top.fill(BitmapFrameBuffer.packARGB(255, 200, 200, 200));
            LayerCompositor.blend(bottom, top, output, LayerCompositor.BlendMode.SCREEN, 1.0);
            int pixel = output.getPixel(0, 0);
            // 255 - ((255-100) * (255-200)) / 255 = 255 - (155 * 55) / 255 = 255 - 33 = 222
            int expected = 255 - ((255 - 100) * (255 - 200)) / 255;
            assertEquals(expected, (pixel >> 16) & 0xFF, "Screen formula");
        }

        @Test
        void overlayDarkBase() {
            // base < 128: result = (2 * base * top) / 255
            BitmapFrameBuffer bottom = new BitmapFrameBuffer(1, 1);
            BitmapFrameBuffer top = new BitmapFrameBuffer(1, 1);
            BitmapFrameBuffer output = new BitmapFrameBuffer(1, 1);
            bottom.fill(BitmapFrameBuffer.packARGB(255, 64, 64, 64));
            top.fill(BitmapFrameBuffer.packARGB(255, 128, 128, 128));
            LayerCompositor.blend(bottom, top, output, LayerCompositor.BlendMode.OVERLAY, 1.0);
            int pixel = output.getPixel(0, 0);
            int expected = (2 * 64 * 128) / 255;
            assertEquals(expected, (pixel >> 16) & 0xFF, "Overlay dark: (2*64*128)/255");
        }

        @Test
        void overlayBrightBase() {
            // base >= 128: result = 255 - (2 * (255-base) * (255-top)) / 255
            BitmapFrameBuffer bottom = new BitmapFrameBuffer(1, 1);
            BitmapFrameBuffer top = new BitmapFrameBuffer(1, 1);
            BitmapFrameBuffer output = new BitmapFrameBuffer(1, 1);
            bottom.fill(BitmapFrameBuffer.packARGB(255, 200, 200, 200));
            top.fill(BitmapFrameBuffer.packARGB(255, 128, 128, 128));
            LayerCompositor.blend(bottom, top, output, LayerCompositor.BlendMode.OVERLAY, 1.0);
            int pixel = output.getPixel(0, 0);
            int expected = 255 - (2 * (255 - 200) * (255 - 128)) / 255;
            assertEquals(expected, (pixel >> 16) & 0xFF, "Overlay bright");
        }

        @Test
        void overlayBoundaryAtExactly128() {
            // base == 128: falls into else branch (>=128)
            BitmapFrameBuffer bottom = new BitmapFrameBuffer(1, 1);
            BitmapFrameBuffer top = new BitmapFrameBuffer(1, 1);
            BitmapFrameBuffer output = new BitmapFrameBuffer(1, 1);
            bottom.fill(BitmapFrameBuffer.packARGB(255, 128, 128, 128));
            top.fill(BitmapFrameBuffer.packARGB(255, 128, 128, 128));
            LayerCompositor.blend(bottom, top, output, LayerCompositor.BlendMode.OVERLAY, 1.0);
            int pixel = output.getPixel(0, 0);
            // else branch: 255 - (2 * 127 * 127) / 255 = 255 - 126 = 129
            int expected = 255 - (2 * (255 - 128) * (255 - 128)) / 255;
            assertEquals(expected, (pixel >> 16) & 0xFF, "Overlay at boundary 128");
        }
    }

    @Nested
    @DisplayName("Opacity Edge Cases")
    class OpacityEdgeCases {
        @Test
        void opacityZeroKeepsBottom() {
            BitmapFrameBuffer bottom = new BitmapFrameBuffer(1, 1);
            BitmapFrameBuffer top = new BitmapFrameBuffer(1, 1);
            BitmapFrameBuffer output = new BitmapFrameBuffer(1, 1);
            bottom.fill(0xFFAA0000);
            top.fill(0xFF00BB00);
            LayerCompositor.blend(bottom, top, output, LayerCompositor.BlendMode.NORMAL, 0.0);
            assertEquals(0xFFAA0000, output.getPixel(0, 0));
        }

        @Test
        void opacityNegativeClampsToZero() {
            BitmapFrameBuffer bottom = new BitmapFrameBuffer(1, 1);
            BitmapFrameBuffer top = new BitmapFrameBuffer(1, 1);
            BitmapFrameBuffer output = new BitmapFrameBuffer(1, 1);
            bottom.fill(0xFFAA0000);
            top.fill(0xFF00BB00);
            LayerCompositor.blend(bottom, top, output, LayerCompositor.BlendMode.NORMAL, -1.0);
            assertEquals(0xFFAA0000, output.getPixel(0, 0));
        }

        @Test
        void opacityAboveOneClampsToOne() {
            BitmapFrameBuffer bottom = new BitmapFrameBuffer(1, 1);
            BitmapFrameBuffer top = new BitmapFrameBuffer(1, 1);
            BitmapFrameBuffer output = new BitmapFrameBuffer(1, 1);
            bottom.fill(0xFFAA0000);
            top.fill(0xFF00BB00);
            LayerCompositor.blend(bottom, top, output, LayerCompositor.BlendMode.NORMAL, 2.0);
            assertEquals(0xFF00BB00, output.getPixel(0, 0));
        }
    }

    @Nested
    @DisplayName("Buffer Size Handling")
    class BufferSizes {
        @Test
        void mismatchedBuffersUsesMinLength() {
            BitmapFrameBuffer small = new BitmapFrameBuffer(2, 2);  // 4 pixels
            BitmapFrameBuffer large = new BitmapFrameBuffer(4, 4);  // 16 pixels
            BitmapFrameBuffer output = new BitmapFrameBuffer(2, 2); // 4 pixels
            small.fill(0xFFFF0000);
            large.fill(0xFF00FF00);
            // Should not crash — blends min(4, 16, 4) = 4 pixels
            assertDoesNotThrow(() ->
                LayerCompositor.blend(small, large, output, LayerCompositor.BlendMode.NORMAL, 1.0));
        }
    }
```

**Step 2: Run tests**

Run: `cd minecraft_plugin && mvn test -Dtest=com.audioviz.bitmap.LayerCompositorTest -pl . 2>&1 | tail -30`
Expected: All tests PASS

**Step 3: Commit**

```bash
git add minecraft_plugin/src/test/java/com/audioviz/bitmap/LayerCompositorTest.java
git commit -m "test: expand LayerCompositor tests — blend mode math, opacity edges, buffer sizes"
```

---

### Task 7: EffectsProcessor — Expand Tests

**Files:**
- Modify: `minecraft_plugin/src/test/java/com/audioviz/bitmap/EffectsProcessorTest.java`

**Step 1: Add effect-specific tests**

```java
    @Nested
    @DisplayName("RGB Split Effect")
    class RgbSplit {
        @Test
        void rgbSplitSeparatesChannels() {
            EffectsProcessor proc = new EffectsProcessor();
            proc.setRgbSplitEnabled(true);
            proc.setRgbSplitOffset(1);

            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 1);
            // Set pixel at (1,0) to white; others black
            buf.setPixel(1, 0, 0xFFFFFFFF);
            proc.process(buf, null, 0);

            // Red channel shifts left: pixel (0,0) should gain red from (1,0)
            int left = buf.getPixel(0, 0);
            int rLeft = (left >> 16) & 0xFF;
            assertEquals(255, rLeft, "Red should shift left by offset");

            // Blue channel shifts right: pixel (2,0) should gain blue from (1,0)
            int right = buf.getPixel(2, 0);
            int bRight = right & 0xFF;
            assertEquals(255, bRight, "Blue should shift right by offset");
        }

        @Test
        void rgbSplitDisabledNoChange() {
            EffectsProcessor proc = new EffectsProcessor();
            proc.setRgbSplitEnabled(false);
            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 1);
            buf.setPixel(1, 0, 0xFFFFFFFF);
            int before = buf.getPixel(0, 0);
            proc.process(buf, null, 0);
            assertEquals(before, buf.getPixel(0, 0), "Disabled RGB split should not modify");
        }
    }

    @Nested
    @DisplayName("Bit Crush Effect")
    class BitCrush {
        @Test
        void colorQuantization() {
            EffectsProcessor proc = new EffectsProcessor();
            proc.setBitCrushEnabled(true);
            proc.setBitCrushColorLevels(2); // Only 0 or 255
            proc.setBitCrushPixelSize(1);   // No spatial downsampling

            BitmapFrameBuffer buf = new BitmapFrameBuffer(1, 1);
            buf.fill(BitmapFrameBuffer.packARGB(255, 100, 200, 50));
            proc.process(buf, null, 0);

            int pixel = buf.getPixel(0, 0);
            // With 2 levels: each channel rounds to 0 or 255
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            assertTrue(r == 0 || r == 255, "R should be quantized to 0 or 255: " + r);
            assertTrue(g == 0 || g == 255, "G should be quantized to 0 or 255: " + g);
            assertTrue(b == 0 || b == 255, "B should be quantized to 0 or 255: " + b);
        }

        @Test
        void spatialDownsampling() {
            EffectsProcessor proc = new EffectsProcessor();
            proc.setBitCrushEnabled(true);
            proc.setBitCrushColorLevels(256); // No color change
            proc.setBitCrushPixelSize(2);

            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.setPixel(0, 0, 0xFFFF0000);
            buf.setPixel(1, 0, 0xFF00FF00);
            buf.setPixel(0, 1, 0xFF0000FF);
            buf.setPixel(1, 1, 0xFFFFFFFF);
            proc.process(buf, null, 0);

            // In a 2x2 block, all should match the leader (0,0)
            assertEquals(buf.getPixel(0, 0), buf.getPixel(1, 0), "Block should match leader");
            assertEquals(buf.getPixel(0, 0), buf.getPixel(0, 1), "Block should match leader");
            assertEquals(buf.getPixel(0, 0), buf.getPixel(1, 1), "Block should match leader");
        }
    }

    @Nested
    @DisplayName("Edge Flash Effect")
    class EdgeFlash {
        @Test
        void edgeFlashAffectsEdgesOnly() {
            EffectsProcessor proc = new EffectsProcessor();
            proc.setEdgeFlashEnabled(true);
            proc.setEdgeFlashColor(0xFFFFFFFF);
            proc.setEdgeFlashWidth(1);

            BitmapFrameBuffer buf = new BitmapFrameBuffer(8, 8);
            buf.fill(0xFF000000); // All black

            // Simulate beat to trigger edge flash
            AudioState beat = new AudioState(new double[5], 0.5, true, 0.8, 1);
            proc.process(buf, beat, 0);

            // Edge pixel should be modified (blended toward white)
            int edge = buf.getPixel(0, 0);
            assertTrue(((edge >> 16) & 0xFF) > 0, "Edge should be brightened");

            // Center pixel should still be black (distance from edge = 3)
            int center = buf.getPixel(4, 4);
            assertEquals(0xFF000000, center, "Center should be unaffected");
        }
    }

    @Nested
    @DisplayName("Strobe Decay")
    class StrobeDecay {
        @Test
        void strobeDecaysAcrossFrames() {
            EffectsProcessor proc = new EffectsProcessor();
            proc.setStrobeEnabled(true);
            proc.setStrobeDivisor(1);

            BitmapFrameBuffer buf = new BitmapFrameBuffer(1, 1);
            AudioState beat = new AudioState(new double[5], 0.5, true, 0.8, 1);

            buf.fill(0xFF000000);
            proc.process(buf, beat, 0);
            int flash1 = buf.getPixel(0, 0);

            // Second frame — no beat, strobe should decay
            buf.fill(0xFF000000);
            AudioState noBeat = new AudioState(new double[5], 0.5, false, 0.0, 2);
            proc.process(buf, noBeat, 1);
            int flash2 = buf.getPixel(0, 0);

            int brightness1 = ((flash1 >> 16) & 0xFF) + ((flash1 >> 8) & 0xFF) + (flash1 & 0xFF);
            int brightness2 = ((flash2 >> 16) & 0xFF) + ((flash2 >> 8) & 0xFF) + (flash2 & 0xFF);
            assertTrue(brightness2 < brightness1, "Strobe should decay: " + brightness2 + " >= " + brightness1);
        }
    }

    @Nested
    @DisplayName("Null Audio Safety")
    class NullAudio {
        @Test
        void processWithNullAudioDoesNotCrash() {
            EffectsProcessor proc = new EffectsProcessor();
            proc.setStrobeEnabled(true);
            proc.setBeatFlashEnabled(true);
            proc.setEdgeFlashEnabled(true);
            proc.setRgbSplitEnabled(true);
            proc.setBitCrushEnabled(true);

            BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 4);
            buf.fill(0xFFFFFFFF);
            assertDoesNotThrow(() -> proc.process(buf, null, 0));
        }
    }
```

**Step 2: Run tests**

Run: `cd minecraft_plugin && mvn test -Dtest=com.audioviz.bitmap.EffectsProcessorTest -pl . 2>&1 | tail -30`
Expected: All tests PASS

**Step 3: Commit**

```bash
git add minecraft_plugin/src/test/java/com/audioviz/bitmap/EffectsProcessorTest.java
git commit -m "test: expand EffectsProcessor tests — RGB split, bit crush, edge flash, strobe decay"
```

---

### Task 8: AudioState — Expand Tests

**Files:**
- Modify: `minecraft_plugin/src/test/java/com/audioviz/patterns/AudioStateTest.java`

**Step 1: Add boundary tests**

```java
    @Nested
    @DisplayName("Boundary Values")
    class BoundaryValues {
        @Test
        void nanBandsReturnAsIs() {
            double[] bands = {Double.NaN, 0.5, 0.5, 0.5, 0.5};
            AudioState state = new AudioState(bands, 0.5, false, 0.0, 1);
            assertTrue(Double.isNaN(state.getBand(0)), "NaN bands should be returned as-is");
        }

        @Test
        void negativeBandIndex() {
            AudioState state = AudioState.silent();
            assertEquals(0.0, state.getBand(-1));
        }

        @Test
        void bandIndexBeyondLength() {
            AudioState state = AudioState.silent();
            assertEquals(0.0, state.getBand(5));
            assertEquals(0.0, state.getBand(100));
        }

        @Test
        void silentFactoryInvariants() {
            AudioState s = AudioState.silent();
            assertEquals(5, s.getBands().length);
            assertEquals(0.0, s.getAmplitude());
            assertFalse(s.isBeat());
            assertEquals(0.0, s.getBeatIntensity());
            assertEquals(0, s.getFrame());
            assertEquals(0.0, s.getTempoConfidence());
            assertEquals(0.0, s.getBeatPhase());
        }

        @Test
        void forTestWithBeat() {
            AudioState s = AudioState.forTest(100, true);
            assertTrue(s.isBeat());
            assertEquals(0.8, s.getBeatIntensity(), 0.001);
            assertEquals(100, s.getFrame());
        }

        @Test
        void forTestWithoutBeat() {
            AudioState s = AudioState.forTest(50, false);
            assertFalse(s.isBeat());
            assertEquals(0.0, s.getBeatIntensity(), 0.001);
        }

        @Test
        void extendedConstructor() {
            AudioState state = new AudioState(
                new double[]{0.1, 0.2, 0.3, 0.4, 0.5},
                0.75, true, 0.9, 0.85, 0.42, 999);
            assertEquals(0.85, state.getTempoConfidence(), 0.001);
            assertEquals(0.42, state.getBeatPhase(), 0.001);
            assertEquals(999, state.getFrame());
        }

        @Test
        void namedBandAccessors() {
            double[] bands = {0.1, 0.2, 0.3, 0.4, 0.5};
            AudioState state = new AudioState(bands, 0.5, false, 0.0, 1);
            assertEquals(0.1, state.getBass(), 0.001);
            assertEquals(0.3, state.getMid(), 0.001);
            assertEquals(0.4, state.getHighMid(), 0.001);
            assertEquals(0.5, state.getHigh(), 0.001);
        }
    }
```

**Step 2: Run tests**

Run: `cd minecraft_plugin && mvn test -Dtest=com.audioviz.patterns.AudioStateTest -pl . 2>&1 | tail -30`
Expected: All tests PASS

**Step 3: Commit**

```bash
git add minecraft_plugin/src/test/java/com/audioviz/patterns/AudioStateTest.java
git commit -m "test: expand AudioState tests — boundary values, named accessors, factory invariants"
```

---

### Task 9: Run Full Test Suite

**Step 1: Run all plugin tests**

Run: `cd minecraft_plugin && mvn test -pl . 2>&1 | tail -40`
Expected: All tests PASS, 0 failures, 0 errors

**Step 2: Verify test count increased**

Run: `cd minecraft_plugin && mvn test -pl . 2>&1 | grep "Tests run"`
Expected: Total test count should be ~120-150+ (up from ~80)

**Step 3: Final commit (if any fixups needed)**

```bash
git add -A minecraft_plugin/src/test/
git commit -m "test: java plugin pure-logic test expansion complete"
```
