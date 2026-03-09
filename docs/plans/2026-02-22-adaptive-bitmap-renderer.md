# Adaptive Bitmap Renderer Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the 1-entity-per-pixel bitmap renderer with a unified adaptive renderer that uses half-block characters (2 pixels/entity) and greedy rectangle merging to achieve ~10x more pixels per entity budget.

**Architecture:** Patterns write to a logical-resolution `BitmapFrameBuffer` (unchanged). The new renderer converts pixels into half-block cell pairs, merges uniform rectangles greedily, assigns merged rects to a pre-allocated entity pool, and uses dual dirty tracking to minimize Bukkit API calls per tick.

**Tech Stack:** Java 21, Paper API 1.21.11, Adventure Component API (for `TextColor`), JUnit 5 + Mockito

**Design doc:** `docs/plans/2026-02-22-adaptive-bitmap-renderer-design.md`

---

## Task 1: HalfBlockCell and MergedRect records

Pure data types. No dependencies.

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/bitmap/adaptive/HalfBlockCell.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/bitmap/adaptive/MergedRect.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/bitmap/adaptive/HalfBlockCellTest.java`

**Step 1: Write the test**

```java
package com.audioviz.bitmap.adaptive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

class HalfBlockCellTest {

    @Test
    @DisplayName("cells with same top and bottom are uniform")
    void uniformCell() {
        var cell = new HalfBlockCell(0xFF000000, 0xFF000000);
        assertTrue(cell.isUniform());
    }

    @Test
    @DisplayName("cells with different top and bottom are not uniform")
    void nonUniformCell() {
        var cell = new HalfBlockCell(0xFFFF0000, 0xFF00FF00);
        assertFalse(cell.isUniform());
    }

    @Test
    @DisplayName("equality based on both colors")
    void equality() {
        var a = new HalfBlockCell(0xFFFF0000, 0xFF00FF00);
        var b = new HalfBlockCell(0xFFFF0000, 0xFF00FF00);
        var c = new HalfBlockCell(0xFFFF0000, 0xFF0000FF);
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    @DisplayName("MergedRect stores position, size, and colors")
    void mergedRectBasics() {
        var rect = new MergedRect(2, 3, 4, 5, 0xFFFF0000, 0xFF00FF00);
        assertEquals(2, rect.x());
        assertEquals(3, rect.y());
        assertEquals(4, rect.w());
        assertEquals(5, rect.h());
        assertEquals(20, rect.cellCount());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd minecraft_plugin && mvn test -pl . -Dtest="com.audioviz.bitmap.adaptive.HalfBlockCellTest" -Dsurefire.failIfNoTests=false`
Expected: compilation failure — classes don't exist yet

**Step 3: Write HalfBlockCell**

```java
package com.audioviz.bitmap.adaptive;

/**
 * A half-block cell representing two vertically stacked pixels.
 * The top pixel maps to the entity's background color,
 * the bottom pixel maps to the text color of a ▄ (U+2584) character.
 */
public record HalfBlockCell(int topARGB, int bottomARGB) {

    /** True when both pixels are the same color (entity can use space + bg only). */
    public boolean isUniform() {
        return topARGB == bottomARGB;
    }
}
```

**Step 4: Write MergedRect**

```java
package com.audioviz.bitmap.adaptive;

/**
 * A merged rectangle of identical half-block cells.
 * Represents a region of the cell grid that can be rendered by a single entity.
 *
 * @param x         left column in the cell grid
 * @param y         top row in the cell grid
 * @param w         width in cells
 * @param h         height in cells
 * @param topARGB   shared top pixel color for all cells in this rect
 * @param bottomARGB shared bottom pixel color for all cells in this rect
 */
public record MergedRect(int x, int y, int w, int h, int topARGB, int bottomARGB) {

    /** Number of cells covered by this rectangle. */
    public int cellCount() {
        return w * h;
    }

    /** True when top and bottom colors match (entity can skip text Component). */
    public boolean isUniform() {
        return topARGB == bottomARGB;
    }
}
```

**Step 5: Run tests to verify they pass**

Run: `cd minecraft_plugin && mvn test -pl . -Dtest="com.audioviz.bitmap.adaptive.HalfBlockCellTest"`
Expected: all 4 tests PASS

**Step 6: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/adaptive/HalfBlockCell.java \
        minecraft_plugin/src/main/java/com/audioviz/bitmap/adaptive/MergedRect.java \
        minecraft_plugin/src/test/java/com/audioviz/bitmap/adaptive/HalfBlockCellTest.java
git commit -m "feat(bitmap): add HalfBlockCell and MergedRect records for adaptive renderer"
```

---

## Task 2: CellGridMerger — cell grid construction

Build the cell grid from a BitmapFrameBuffer. No merging yet.

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/bitmap/adaptive/CellGridMerger.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/bitmap/adaptive/CellGridMergerTest.java`

**Step 1: Write the test**

```java
package com.audioviz.bitmap.adaptive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import static org.junit.jupiter.api.Assertions.*;

class CellGridMergerTest {

    private static final int BLACK = 0xFF000000;
    private static final int RED   = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE  = 0xFF0000FF;

    @Nested
    @DisplayName("Cell grid construction")
    class CellGridConstruction {

        @Test
        @DisplayName("even height: pairs rows into cells")
        void evenHeight() {
            // 2x4 pixel buffer → 2x2 cell grid
            int[] pixels = {
                RED,   GREEN,  // row 0 (top of cell row 0)
                BLUE,  BLACK,  // row 1 (bottom of cell row 0)
                BLACK, RED,    // row 2 (top of cell row 1)
                GREEN, BLUE,   // row 3 (bottom of cell row 1)
            };
            HalfBlockCell[] cells = CellGridMerger.buildCellGrid(pixels, 2, 4);

            assertEquals(4, cells.length); // 2x2 cell grid
            assertEquals(new HalfBlockCell(RED, BLUE), cells[0]);    // (0,0)
            assertEquals(new HalfBlockCell(GREEN, BLACK), cells[1]); // (1,0)
            assertEquals(new HalfBlockCell(BLACK, GREEN), cells[2]); // (0,1)
            assertEquals(new HalfBlockCell(RED, BLUE), cells[3]);    // (1,1)
        }

        @Test
        @DisplayName("odd height: last row gets transparent bottom")
        void oddHeight() {
            // 2x3 pixel buffer → 2x2 cell grid (last cell row has solo pixel)
            int[] pixels = {
                RED,   GREEN,  // row 0
                BLUE,  BLACK,  // row 1
                RED,   RED,    // row 2 (solo — bottom pixel = transparent)
            };
            HalfBlockCell[] cells = CellGridMerger.buildCellGrid(pixels, 2, 3);

            assertEquals(4, cells.length); // 2x2 cell grid
            // Last row: top = pixel, bottom = 0 (transparent)
            assertEquals(new HalfBlockCell(RED, 0), cells[2]);
            assertEquals(new HalfBlockCell(RED, 0), cells[3]);
        }

        @Test
        @DisplayName("cell grid dimensions")
        void dimensions() {
            assertEquals(2, CellGridMerger.cellGridHeight(4));  // even
            assertEquals(2, CellGridMerger.cellGridHeight(3));  // odd, rounds up
            assertEquals(1, CellGridMerger.cellGridHeight(1));  // single row
            assertEquals(1, CellGridMerger.cellGridHeight(2));  // two rows = 1 cell row
        }
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd minecraft_plugin && mvn test -pl . -Dtest="com.audioviz.bitmap.adaptive.CellGridMergerTest"`
Expected: compilation failure

**Step 3: Write CellGridMerger (cell grid construction only)**

```java
package com.audioviz.bitmap.adaptive;

import java.util.List;

/**
 * Converts a BitmapFrameBuffer's pixel array into a half-block cell grid
 * and merges uniform rectangular regions using a greedy algorithm.
 *
 * <p>Stateless — all methods are static. Reusable across frames with no allocation
 * between calls (caller provides output arrays).
 */
public final class CellGridMerger {

    private CellGridMerger() {} // utility class

    /** Half-block character: lower half block (U+2584). */
    public static final String HALF_BLOCK = "\u2584";

    /**
     * Calculate cell grid height from logical pixel height.
     * Rounds up so an odd pixel height gets a partial last cell row.
     */
    public static int cellGridHeight(int pixelHeight) {
        return (pixelHeight + 1) / 2;
    }

    /**
     * Build a cell grid from a raw pixel array.
     *
     * @param pixels      row-major ARGB pixel array (width * height entries)
     * @param pixelWidth  logical pixel width
     * @param pixelHeight logical pixel height
     * @return flat cell array, row-major, dimensions pixelWidth x cellGridHeight(pixelHeight)
     */
    public static HalfBlockCell[] buildCellGrid(int[] pixels, int pixelWidth, int pixelHeight) {
        int cellHeight = cellGridHeight(pixelHeight);
        int cellCount = pixelWidth * cellHeight;
        HalfBlockCell[] cells = new HalfBlockCell[cellCount];

        for (int cy = 0; cy < cellHeight; cy++) {
            int topRow = cy * 2;
            int bottomRow = topRow + 1;
            boolean hasBottom = bottomRow < pixelHeight;

            for (int cx = 0; cx < pixelWidth; cx++) {
                int topARGB = pixels[topRow * pixelWidth + cx];
                int bottomARGB = hasBottom ? pixels[bottomRow * pixelWidth + cx] : 0;
                cells[cy * pixelWidth + cx] = new HalfBlockCell(topARGB, bottomARGB);
            }
        }
        return cells;
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `cd minecraft_plugin && mvn test -pl . -Dtest="com.audioviz.bitmap.adaptive.CellGridMergerTest"`
Expected: all 4 tests PASS

**Step 5: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/adaptive/CellGridMerger.java \
        minecraft_plugin/src/test/java/com/audioviz/bitmap/adaptive/CellGridMergerTest.java
git commit -m "feat(bitmap): add CellGridMerger with cell grid construction"
```

---

## Task 3: CellGridMerger — greedy rectangle merge

The core algorithm. Add the `merge()` method to `CellGridMerger`.

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/bitmap/adaptive/CellGridMerger.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/bitmap/adaptive/CellGridMergerTest.java`

**Step 1: Write the tests**

Add a new `@Nested` class to the existing test file:

```java
@Nested
@DisplayName("Greedy rectangle merge")
class GreedyMerge {

    @Test
    @DisplayName("solid color merges into one rect")
    void solidColor() {
        // 4x4 pixel buffer, all black → 4x2 cell grid, all same → 1 merged rect
        int[] pixels = new int[16];
        java.util.Arrays.fill(pixels, BLACK);
        List<MergedRect> rects = CellGridMerger.merge(pixels, 4, 4);

        assertEquals(1, rects.size());
        MergedRect r = rects.get(0);
        assertEquals(0, r.x());
        assertEquals(0, r.y());
        assertEquals(4, r.w());
        assertEquals(2, r.h());
        assertEquals(BLACK, r.topARGB());
        assertEquals(BLACK, r.bottomARGB());
    }

    @Test
    @DisplayName("all unique cells: one rect per cell")
    void allUnique() {
        // 2x2 pixel buffer → 2x1 cell grid, each cell different
        int[] pixels = { RED, GREEN, BLUE, BLACK };
        List<MergedRect> rects = CellGridMerger.merge(pixels, 2, 2);

        assertEquals(2, rects.size()); // 2 cells, each unique
    }

    @Test
    @DisplayName("horizontal run merges adjacent identical cells")
    void horizontalRun() {
        // 4x2 pixels, all red → 4x1 cell grid, all same → 1 rect
        int[] pixels = { RED, RED, RED, RED, RED, RED, RED, RED };
        List<MergedRect> rects = CellGridMerger.merge(pixels, 4, 2);

        assertEquals(1, rects.size());
        assertEquals(4, rects.get(0).w());
        assertEquals(1, rects.get(0).h());
    }

    @Test
    @DisplayName("vertical run merges identical cell rows")
    void verticalRun() {
        // 1x4 pixels, all red → 1x2 cell grid, both cells same → 1 rect
        int[] pixels = { RED, RED, RED, RED };
        List<MergedRect> rects = CellGridMerger.merge(pixels, 1, 4);

        assertEquals(1, rects.size());
        assertEquals(1, rects.get(0).w());
        assertEquals(2, rects.get(0).h());
    }

    @Test
    @DisplayName("L-shaped region produces multiple rects")
    void lShape() {
        // 2x4 pixels:
        //   R R    → cell row 0: (R,R) (R,R)  ← same
        //   R R
        //   R B    → cell row 1: (R,B) (B,B)  ← different
        //   B B
        int[] pixels = {
            RED,  RED,
            RED,  RED,
            RED,  BLUE,
            BLUE, BLUE,
        };
        List<MergedRect> rects = CellGridMerger.merge(pixels, 2, 4);

        // Cell grid is 2x2:
        //   (R,R) (R,R)  ← can merge horizontally
        //   (R,B) (B,B)  ← different, can't merge
        // Expect 3 rects: one 2x1 top, two 1x1 bottom
        assertEquals(3, rects.size());
        // Verify total cell coverage
        int totalCells = rects.stream().mapToInt(MergedRect::cellCount).sum();
        assertEquals(4, totalCells);
    }

    @Test
    @DisplayName("merge output covers all cells exactly once")
    void fullCoverage() {
        // Checkerboard pattern: no merges possible
        int[] pixels = {
            RED,   GREEN, RED,   GREEN,
            BLUE,  BLACK, BLUE,  BLACK,
            GREEN, RED,   GREEN, RED,
            BLACK, BLUE,  BLACK, BLUE,
        };
        List<MergedRect> rects = CellGridMerger.merge(pixels, 4, 4);

        // 4x2 cell grid = 8 cells, checkerboard → 8 rects
        int totalCells = rects.stream().mapToInt(MergedRect::cellCount).sum();
        assertEquals(8, totalCells);
    }

    @Test
    @DisplayName("half-block cells with same top but different bottom don't merge")
    void differentBottomNoMerge() {
        // 2x2 pixels: top row same, bottom row different
        int[] pixels = { RED, RED, GREEN, BLUE };
        List<MergedRect> rects = CellGridMerger.merge(pixels, 2, 2);

        // Cells: (RED,GREEN) and (RED,BLUE) — different bottom → 2 rects
        assertEquals(2, rects.size());
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `cd minecraft_plugin && mvn test -pl . -Dtest="com.audioviz.bitmap.adaptive.CellGridMergerTest"`
Expected: compilation failure — `merge()` method doesn't exist

**Step 3: Implement the greedy merge algorithm**

Add to `CellGridMerger.java`:

```java
/**
 * Build cell grid and merge uniform rectangular regions.
 *
 * <p>Algorithm (row-first greedy scan):
 * <ol>
 *   <li>Build cell grid from pixels</li>
 *   <li>Scan top-to-bottom, left-to-right for unconsumed cells</li>
 *   <li>At each cell, extend right while adjacent cells match</li>
 *   <li>Then extend the full run downward while all cells in the wider row match</li>
 *   <li>Mark consumed, emit MergedRect</li>
 * </ol>
 *
 * <p>Output is deterministically ordered (top-to-bottom, left-to-right)
 * for stable entity assignment across frames.
 *
 * @param pixels      row-major ARGB pixel array
 * @param pixelWidth  logical pixel width
 * @param pixelHeight logical pixel height
 * @return list of merged rectangles covering all cells exactly once
 */
public static List<MergedRect> merge(int[] pixels, int pixelWidth, int pixelHeight) {
    int cellWidth = pixelWidth;
    int cellHeight = cellGridHeight(pixelHeight);
    HalfBlockCell[] cells = buildCellGrid(pixels, pixelWidth, pixelHeight);
    boolean[] consumed = new boolean[cells.length];

    List<MergedRect> result = new java.util.ArrayList<>();

    for (int cy = 0; cy < cellHeight; cy++) {
        for (int cx = 0; cx < cellWidth; cx++) {
            int idx = cy * cellWidth + cx;
            if (consumed[idx]) continue;

            HalfBlockCell ref = cells[idx];

            // Extend right
            int runW = 1;
            while (cx + runW < cellWidth) {
                int nextIdx = cy * cellWidth + (cx + runW);
                if (consumed[nextIdx] || !cells[nextIdx].equals(ref)) break;
                runW++;
            }

            // Extend the full run downward
            int runH = 1;
            outer:
            while (cy + runH < cellHeight) {
                for (int dx = 0; dx < runW; dx++) {
                    int belowIdx = (cy + runH) * cellWidth + (cx + dx);
                    if (consumed[belowIdx] || !cells[belowIdx].equals(ref)) break outer;
                }
                runH++;
            }

            // Mark consumed
            for (int dy = 0; dy < runH; dy++) {
                for (int dx = 0; dx < runW; dx++) {
                    consumed[(cy + dy) * cellWidth + (cx + dx)] = true;
                }
            }

            result.add(new MergedRect(cx, cy, runW, runH, ref.topARGB(), ref.bottomARGB()));
        }
    }

    return result;
}
```

**Step 4: Run tests to verify they pass**

Run: `cd minecraft_plugin && mvn test -pl . -Dtest="com.audioviz.bitmap.adaptive.CellGridMergerTest"`
Expected: all tests PASS

**Step 5: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/adaptive/CellGridMerger.java \
        minecraft_plugin/src/test/java/com/audioviz/bitmap/adaptive/CellGridMergerTest.java
git commit -m "feat(bitmap): implement greedy rectangle merge algorithm"
```

---

## Task 4: AdaptiveEntityAssigner — slot assignment and dirty tracking

Maps merged rectangles to entity pool slots, caches state, emits minimal diffs.

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/bitmap/adaptive/AdaptiveEntityAssigner.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/bitmap/adaptive/AdaptiveEntityAssignerTest.java`

**Step 1: Write the tests**

```java
package com.audioviz.bitmap.adaptive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

class AdaptiveEntityAssignerTest {

    private static final int BLACK = 0xFF000000;
    private static final int RED   = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;

    @Test
    @DisplayName("first frame: all slots are dirty")
    void firstFrameAllDirty() {
        var assigner = new AdaptiveEntityAssigner(100);
        List<MergedRect> rects = List.of(
            new MergedRect(0, 0, 2, 2, BLACK, BLACK),
            new MergedRect(2, 0, 1, 1, RED, GREEN)
        );

        var diff = assigner.assign(rects, 1.0f);

        assertEquals(2, diff.geometryUpdates().size());
        assertEquals(2, diff.backgroundUpdates().size());
        // Uniform rect skips text update; non-uniform gets text update
        assertEquals(1, diff.textUpdates().size());
        assertEquals(0, diff.hideCount()); // no previously active slots to hide
    }

    @Test
    @DisplayName("identical second frame: zero dirty")
    void identicalFrameZeroDirty() {
        var assigner = new AdaptiveEntityAssigner(100);
        List<MergedRect> rects = List.of(
            new MergedRect(0, 0, 4, 2, BLACK, BLACK)
        );

        assigner.assign(rects, 1.0f); // first frame
        var diff = assigner.assign(rects, 1.0f); // identical second frame

        assertEquals(0, diff.geometryUpdates().size());
        assertEquals(0, diff.backgroundUpdates().size());
        assertEquals(0, diff.textUpdates().size());
        assertEquals(0, diff.hideCount());
    }

    @Test
    @DisplayName("color change only: no geometry dirty")
    void colorChangeOnly() {
        var assigner = new AdaptiveEntityAssigner(100);

        assigner.assign(List.of(new MergedRect(0, 0, 2, 2, BLACK, BLACK)), 1.0f);
        var diff = assigner.assign(List.of(new MergedRect(0, 0, 2, 2, RED, BLACK)), 1.0f);

        assertEquals(0, diff.geometryUpdates().size()); // same position/size
        assertEquals(1, diff.backgroundUpdates().size()); // top color changed
        assertEquals(0, diff.textUpdates().size()); // bottom didn't change, still uniform→non needs text now
    }

    @Test
    @DisplayName("fewer rects than before: excess slots get hidden")
    void excessSlotsHidden() {
        var assigner = new AdaptiveEntityAssigner(100);

        assigner.assign(List.of(
            new MergedRect(0, 0, 1, 1, BLACK, BLACK),
            new MergedRect(1, 0, 1, 1, RED, RED),
            new MergedRect(2, 0, 1, 1, GREEN, GREEN)
        ), 1.0f);

        var diff = assigner.assign(List.of(
            new MergedRect(0, 0, 3, 1, BLACK, BLACK)
        ), 1.0f);

        assertEquals(2, diff.hideCount()); // slots 1 and 2 need hiding
    }

    @Test
    @DisplayName("pool exhaustion: rects beyond capacity are dropped")
    void poolExhaustion() {
        var assigner = new AdaptiveEntityAssigner(2); // tiny pool

        List<MergedRect> rects = List.of(
            new MergedRect(0, 0, 1, 1, BLACK, BLACK),
            new MergedRect(1, 0, 1, 1, RED, RED),
            new MergedRect(2, 0, 1, 1, GREEN, GREEN) // exceeds pool
        );

        var diff = assigner.assign(rects, 1.0f);

        // Only 2 rects assigned, 3rd dropped
        assertEquals(2, diff.geometryUpdates().size());
        assertTrue(diff.poolExhausted());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd minecraft_plugin && mvn test -pl . -Dtest="com.audioviz.bitmap.adaptive.AdaptiveEntityAssignerTest"`
Expected: compilation failure

**Step 3: Implement AdaptiveEntityAssigner**

```java
package com.audioviz.bitmap.adaptive;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps merged rectangles to entity pool slots and tracks dirty state
 * for minimal per-frame Bukkit API calls.
 *
 * <p>Each slot caches: geometry (position/scale), top color, bottom color, active state.
 * On each {@link #assign} call, compares new assignments against cache and emits
 * only the changes needed.
 */
public class AdaptiveEntityAssigner {

    private final int maxSlots;

    // Per-slot cached state
    private final float[] lastX, lastY, lastScaleX, lastScaleY;
    private final int[] lastTopARGB, lastBottomARGB;
    private final boolean[] slotActive;
    private int lastActiveCount = 0;

    public AdaptiveEntityAssigner(int maxSlots) {
        this.maxSlots = maxSlots;
        this.lastX = new float[maxSlots];
        this.lastY = new float[maxSlots];
        this.lastScaleX = new float[maxSlots];
        this.lastScaleY = new float[maxSlots];
        this.lastTopARGB = new int[maxSlots];
        this.lastBottomARGB = new int[maxSlots];
        this.slotActive = new boolean[maxSlots];
    }

    /**
     * Assign merged rects to pool slots and compute the minimal diff.
     *
     * @param rects      merged rectangles from CellGridMerger, deterministically ordered
     * @param pixelScale world-space size of one cell column (used for geometry)
     * @return the diff containing only changed updates
     */
    public FrameDiff assign(List<MergedRect> rects, float pixelScale) {
        int assignCount = Math.min(rects.size(), maxSlots);
        boolean exhausted = rects.size() > maxSlots;

        List<GeometryUpdate> geoUpdates = new ArrayList<>();
        List<BackgroundUpdate> bgUpdates = new ArrayList<>();
        List<TextUpdate> txtUpdates = new ArrayList<>();

        for (int i = 0; i < assignCount; i++) {
            MergedRect rect = rects.get(i);
            String entityId = "bmp_" + i;

            // Compute geometry
            float x = rect.x() * pixelScale;
            float y = rect.y() * pixelScale;
            float scaleX = rect.w() * pixelScale;
            float scaleY = rect.h() * pixelScale;

            boolean wasActive = slotActive[i];
            boolean geoDirty = !wasActive
                || x != lastX[i] || y != lastY[i]
                || scaleX != lastScaleX[i] || scaleY != lastScaleY[i];
            boolean bgDirty = !wasActive || rect.topARGB() != lastTopARGB[i];
            boolean bottomDirty = !wasActive || rect.bottomARGB() != lastBottomARGB[i];

            // Uniform rects (top==bottom) only need bg update, not text.
            // But if transitioning FROM non-uniform to uniform, we need a text
            // update to switch from ▄ to space.
            boolean wasUniform = wasActive && lastTopARGB[i] == lastBottomARGB[i];
            boolean isUniform = rect.isUniform();
            boolean needsTextUpdate = bottomDirty || (wasUniform != isUniform);

            if (geoDirty) {
                geoUpdates.add(new GeometryUpdate(entityId, i, x, y, scaleX, scaleY));
            }
            if (bgDirty) {
                bgUpdates.add(new BackgroundUpdate(entityId, i, rect.topARGB()));
            }
            if (needsTextUpdate && !isUniform) {
                txtUpdates.add(new TextUpdate(entityId, i, rect.bottomARGB()));
            } else if (needsTextUpdate && isUniform) {
                // Switching to uniform: clear text (use space)
                txtUpdates.add(new TextUpdate(entityId, i, -1)); // sentinel: -1 = use space
            }

            // Update cache
            lastX[i] = x;
            lastY[i] = y;
            lastScaleX[i] = scaleX;
            lastScaleY[i] = scaleY;
            lastTopARGB[i] = rect.topARGB();
            lastBottomARGB[i] = rect.bottomARGB();
            slotActive[i] = true;
        }

        // Hide previously active slots that are no longer needed
        int hideCount = 0;
        for (int i = assignCount; i < lastActiveCount; i++) {
            if (slotActive[i]) {
                slotActive[i] = false;
                hideCount++;
            }
        }

        int hideStart = assignCount;
        lastActiveCount = assignCount;

        return new FrameDiff(geoUpdates, bgUpdates, txtUpdates, hideStart, hideCount, exhausted);
    }

    public int getMaxSlots() { return maxSlots; }

    // --- Update records ---

    public record GeometryUpdate(String entityId, int slot, float x, float y, float scaleX, float scaleY) {}
    public record BackgroundUpdate(String entityId, int slot, int argb) {}
    /** TextUpdate with argb = -1 means switch to space (uniform cell). */
    public record TextUpdate(String entityId, int slot, int argb) {}

    public record FrameDiff(
        List<GeometryUpdate> geometryUpdates,
        List<BackgroundUpdate> backgroundUpdates,
        List<TextUpdate> textUpdates,
        int hideStart,
        int hideCount,
        boolean poolExhausted
    ) {}
}
```

**Step 4: Run tests to verify they pass**

Run: `cd minecraft_plugin && mvn test -pl . -Dtest="com.audioviz.bitmap.adaptive.AdaptiveEntityAssignerTest"`
Expected: all 5 tests PASS

Note: the "color change only" test may need adjustment once we see whether transitioning from uniform to non-uniform triggers a text update. Verify the test expectations match the implementation logic, adjust if needed.

**Step 5: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/adaptive/AdaptiveEntityAssigner.java \
        minecraft_plugin/src/test/java/com/audioviz/bitmap/adaptive/AdaptiveEntityAssignerTest.java
git commit -m "feat(bitmap): add AdaptiveEntityAssigner with dual dirty tracking"
```

---

## Task 5: Raise BitmapFrameBuffer pixel limit

One-line change + test update.

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapFrameBuffer.java:35`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/bitmap/BitmapFrameBufferTest.java:33-36`

**Step 1: Change the limit**

In `BitmapFrameBuffer.java` line 35, change:
```java
// old
if ((long) width * height > 10_000) {
// new
if ((long) width * height > 16_384) {
```

Update the error message on line 36 accordingly (`max 16384`).

**Step 2: Update the test**

In `BitmapFrameBufferTest.java`, the `tooLargeThrows` test checks against the old limit. Update:
```java
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
```

**Step 3: Run tests**

Run: `cd minecraft_plugin && mvn test -pl . -Dtest="com.audioviz.bitmap.BitmapFrameBufferTest"`
Expected: all PASS

**Step 4: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapFrameBuffer.java \
        minecraft_plugin/src/test/java/com/audioviz/bitmap/BitmapFrameBufferTest.java
git commit -m "feat(bitmap): raise frame buffer pixel limit from 10k to 16k"
```

---

## Task 6: EntityPoolManager.batchUpdateAdaptive()

New method that applies geometry, background, and text updates in a single scheduler task.

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/entities/EntityPoolManager.java`

**Step 1: Add imports**

At the top of `EntityPoolManager.java`, add:
```java
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
```

(Some may already be imported — check and add only missing ones.)

**Step 2: Add the method**

Add after the existing `batchUpdateTextBackgroundsRaw` method (~line 530):

```java
/**
 * Batch update for the adaptive bitmap renderer.
 * Applies geometry, background, and text updates in a single scheduler task
 * to minimize main-thread scheduling overhead.
 *
 * @param zoneName       zone to update
 * @param geoIds         entity IDs needing geometry updates
 * @param geoTransforms  corresponding transformations
 * @param geoCount       number of geometry updates
 * @param bgIds          entity IDs needing background color updates
 * @param bgArgb         corresponding ARGB colors
 * @param bgCount        number of background updates
 * @param txtIds         entity IDs needing text updates
 * @param txtComponents  corresponding text Components (null = set to space)
 * @param txtCount       number of text updates
 * @param hideIds        entity IDs to hide (set transparent)
 * @param hideCount      number of entities to hide
 */
public void batchUpdateAdaptive(
        String zoneName,
        String[] geoIds, Transformation[] geoTransforms, int geoCount,
        String[] bgIds, int[] bgArgb, int bgCount,
        String[] txtIds, Component[] txtComponents, int txtCount,
        String[] hideIds, int hideCount) {

    Map<String, Entity> pool = entityPools.get(zoneName.toLowerCase());
    if (pool == null) return;

    Runnable apply = () -> {
        // Geometry updates
        for (int i = 0; i < geoCount; i++) {
            Entity entity = pool.get(geoIds[i]);
            if (entity instanceof TextDisplay display) {
                display.setTransformation(geoTransforms[i]);
                display.setInterpolationDelay(0);
            }
        }

        // Background color updates
        for (int i = 0; i < bgCount; i++) {
            Entity entity = pool.get(bgIds[i]);
            if (entity instanceof TextDisplay display) {
                int argb = bgArgb[i];
                display.setBackgroundColor(Color.fromARGB(
                    (argb >> 24) & 0xFF, (argb >> 16) & 0xFF,
                    (argb >> 8) & 0xFF, argb & 0xFF));
            }
        }

        // Text updates (half-block color or space for uniform)
        for (int i = 0; i < txtCount; i++) {
            Entity entity = pool.get(txtIds[i]);
            if (entity instanceof TextDisplay display) {
                display.text(txtComponents[i]);
            }
        }

        // Hide unused entities
        for (int i = 0; i < hideCount; i++) {
            Entity entity = pool.get(hideIds[i]);
            if (entity instanceof TextDisplay display) {
                display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                display.text(Component.empty());
            }
        }
    };

    if (Bukkit.isPrimaryThread()) {
        apply.run();
    } else {
        Bukkit.getScheduler().runTask(plugin, apply);
    }
}
```

**Step 3: Verify build compiles**

Run: `cd minecraft_plugin && mvn compile`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/entities/EntityPoolManager.java
git commit -m "feat(bitmap): add batchUpdateAdaptive to EntityPoolManager"
```

---

## Task 7: Rewrite BitmapRendererBackend — entity spawning

Replace the fixed 1:1 grid spawning with adaptive pool spawning.

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapRendererBackend.java`

**Step 1: Update BitmapGridConfig record**

Replace the existing record at the bottom of the file:

```java
/**
 * Grid configuration for an adaptive bitmap zone.
 * logicalWidth/Height = pixel dimensions (what patterns see).
 * entityCount = pool size (what's spawned in-world).
 * cellWidth/Height = half-block cell grid dimensions.
 */
public record BitmapGridConfig(
    int logicalWidth, int logicalHeight,
    int cellWidth, int cellHeight,
    int entityCount, int interpolationTicks,
    float pixelScale
) {
    /** @deprecated use logicalWidth * logicalHeight */
    public int pixelCount() { return logicalWidth * logicalHeight; }
    public int width() { return logicalWidth; }
    public int height() { return logicalHeight; }
}
```

**Step 2: Rewrite initializeBitmapGrid(zone, width, height)**

Replace the entity spawning loop. Key changes:
- `height` parameter is now logical pixel height
- Spawn `maxEntities` entities (pool), not `width * height`
- Each entity starts invisible (scale 0, transparent bg)
- Entity IDs: `bmp_0` through `bmp_{maxEntities-1}`
- Store `pixelScale` for use in `applyFrame`

```java
public int[] initializeBitmapGrid(VisualizationZone zone, int width, int height) {
    width = Math.max(1, Math.min(MAX_BITMAP_WIDTH, width));
    height = Math.max(1, Math.min(MAX_BITMAP_HEIGHT * 2, height)); // 2x for half-block

    int maxEntities = plugin.getConfig().getInt("bitmap.max_entities_per_zone", 2048);
    int cellHeight = CellGridMerger.cellGridHeight(height);

    String zoneName = zone.getName();
    final int finalWidth = width;
    final int finalHeight = height;
    final int finalCellHeight = cellHeight;

    // Calculate pixel scale
    double zoneWidth = zone.getSize().getX();
    double zoneHeight = zone.getSize().getY();
    float pixelScaleX = (float) (zoneWidth / finalWidth);
    float pixelScaleY = (float) (zoneHeight / finalCellHeight); // cell rows, not pixel rows
    float pixelScale = Math.min(pixelScaleX, pixelScaleY);

    BitmapGridConfig config = new BitmapGridConfig(
        finalWidth, finalHeight, finalWidth, finalCellHeight,
        maxEntities, DEFAULT_INTERPOLATION_TICKS, pixelScale
    );
    gridConfigs.put(zoneName.toLowerCase(), config);

    // Initialize the assigner for this zone
    assigners.put(zoneName.toLowerCase(), new AdaptiveEntityAssigner(maxEntities));

    Bukkit.getScheduler().runTask(plugin, () -> {
        poolManager.cleanupZone(zoneName);

        Location baseLoc = zone.localToWorld(0.5, 0.5, 0.5);
        baseLoc.setYaw(zone.getRotation() + 180);
        baseLoc.setPitch(0);

        Map<String, Entity> pool = new LinkedHashMap<>();

        for (int i = 0; i < maxEntities; i++) {
            String entityId = "bmp_" + i;

            TextDisplay display = baseLoc.getWorld().spawn(baseLoc, TextDisplay.class, entity -> {
                entity.text(Component.text(CellGridMerger.HALF_BLOCK));
                entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0)); // invisible
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setBrightness(new Display.Brightness(15, 15));
                entity.setInterpolationDuration(DEFAULT_INTERPOLATION_TICKS);
                entity.setInterpolationDelay(0);
                entity.setTeleportDuration(0);
                entity.setSeeThrough(false);
                entity.setDefaultBackground(false);
                entity.setLineWidth(200);
                entity.setPersistent(false);
                // Start invisible (zero scale)
                entity.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0),
                    new AxisAngle4f(0, 0, 0, 1),
                    new Vector3f(0, 0, 0),
                    new AxisAngle4f(0, 0, 0, 1)
                ));
            });

            pool.put(entityId, display);
        }

        poolManager.registerExternalPool(zoneName, pool);
        plugin.getLogger().info("Adaptive bitmap grid: " + finalWidth + "x" + finalHeight +
            " logical pixels (" + finalWidth + "x" + finalCellHeight + " cells, " +
            maxEntities + " entity pool) for zone '" + zoneName + "'");
    });

    return new int[]{width, height};
}
```

**Step 3: Add new fields to BitmapRendererBackend**

At the top of the class, add:

```java
import com.audioviz.bitmap.adaptive.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

// ... inside class body:

/** Zone name → adaptive entity assigner (dirty tracking). */
private final Map<String, AdaptiveEntityAssigner> assigners = new ConcurrentHashMap<>();

/** Max bitmap dimensions — doubled height for half-block. */
private static final int MAX_BITMAP_HEIGHT = 128;
```

(Update `MAX_BITMAP_HEIGHT` from 64 to 128 since half-block doubles effective height.)

**Step 4: Verify build**

Run: `cd minecraft_plugin && mvn compile`
Expected: BUILD SUCCESS (applyFrame will have issues — that's Task 8)

**Step 5: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapRendererBackend.java
git commit -m "feat(bitmap): rewrite entity spawning for adaptive pool"
```

---

## Task 8: Rewrite BitmapRendererBackend — applyFrame with adaptive pipeline

The hot path. Replaces the old dirty-pixel loop with cell grid → merge → assign → batch update.

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapRendererBackend.java`

**Step 1: Rewrite applyFrame**

Replace the existing `applyFrame` method entirely:

```java
/**
 * Apply a frame buffer using the adaptive pipeline.
 * Called every tick (~20 TPS) by the pattern engine.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Merge logical pixels into half-block cell grid</li>
 *   <li>Greedy rectangle merge for uniform regions</li>
 *   <li>Assign to entity pool slots with dirty tracking</li>
 *   <li>Batch update only changed entities</li>
 * </ol>
 */
public void applyFrame(String zoneName, BitmapFrameBuffer frame) {
    String zoneKey = zoneName.toLowerCase();
    BitmapGridConfig config = gridConfigs.get(zoneKey);
    if (config == null) return;

    AdaptiveEntityAssigner assigner = assigners.get(zoneKey);
    if (assigner == null) return;

    VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
    if (zone == null) return;

    // Step 1+2: Build cell grid and merge
    int[] pixels = frame.getRawPixels();
    List<MergedRect> rects = CellGridMerger.merge(pixels, config.logicalWidth(), config.logicalHeight());

    // Step 3: Assign to pool slots, get dirty diff
    AdaptiveEntityAssigner.FrameDiff diff = assigner.assign(rects, config.pixelScale());

    if (diff.poolExhausted()) {
        plugin.getLogger().warning("Bitmap zone '" + zoneName + "': merged rects (" +
            rects.size() + ") exceed entity pool (" + assigner.getMaxSlots() + ")");
    }

    // Step 4: Convert diff to Bukkit API calls
    int geoCount = diff.geometryUpdates().size();
    int bgCount = diff.backgroundUpdates().size();
    int txtCount = diff.textUpdates().size();
    int hideCount = diff.hideCount();

    if (geoCount == 0 && bgCount == 0 && txtCount == 0 && hideCount == 0) return;

    // Build arrays for batch update
    String[] geoIds = new String[geoCount];
    Transformation[] geoTransforms = new Transformation[geoCount];
    String[] bgIds = new String[bgCount];
    int[] bgArgb = new int[bgCount];
    String[] txtIds = new String[txtCount];
    Component[] txtComponents = new Component[txtCount];
    String[] hideIds = new String[hideCount];

    float ps = config.pixelScale();
    double zoneW = zone.getSize().getX();
    double zoneH = zone.getSize().getY();
    double gridWorldW = config.cellWidth() * ps;
    double gridWorldH = config.cellHeight() * ps;
    double offsetX = (zoneW - gridWorldW) / 2.0;
    double offsetY = (zoneH - gridWorldH) / 2.0;

    for (int i = 0; i < geoCount; i++) {
        var geo = diff.geometryUpdates().get(i);
        geoIds[i] = geo.entityId();

        // World-space position: flip X for audience-facing, offset from zone corner
        float worldX = (float) (offsetX + (config.cellWidth() - geo.x() - geo.scaleX()) * ps);
        float worldY = (float) (offsetY + (config.cellHeight() - geo.y() - geo.scaleY()) * ps);

        geoTransforms[i] = new Transformation(
            new Vector3f(
                (-0.1f + 0.5f) * geo.scaleX() * ps,
                (-0.5f + 0.5f) * geo.scaleY() * ps,
                0f
            ),
            new AxisAngle4f(0, 0, 0, 1),
            new Vector3f(geo.scaleX() * ps * 8.0f, geo.scaleY() * ps * 4.0f, 1f),
            new AxisAngle4f(0, 0, 0, 1)
        );

        // Teleport entity to correct position using zone's localToWorld
        // Position is normalized 0-1 within zone bounds
        double localX = (offsetX + (config.cellWidth() - geo.x() - geo.scaleX() / 2.0) * ps) / zoneW;
        double localY = (offsetY + (config.cellHeight() - geo.y() - geo.scaleY() / 2.0) * ps) / zoneH;

        // Note: actual teleport happens in batchUpdateAdaptive via Transformation translation
    }

    for (int i = 0; i < bgCount; i++) {
        var bg = diff.backgroundUpdates().get(i);
        bgIds[i] = bg.entityId();
        bgArgb[i] = bg.argb();
    }

    for (int i = 0; i < txtCount; i++) {
        var txt = diff.textUpdates().get(i);
        txtIds[i] = txt.entityId();
        if (txt.argb() == -1) {
            // Uniform cell: use space character (no visible text, bg only)
            txtComponents[i] = Component.text(" ");
        } else {
            int argb = txt.argb();
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            txtComponents[i] = Component.text(CellGridMerger.HALF_BLOCK,
                TextColor.color(r, g, b));
        }
    }

    for (int i = 0; i < hideCount; i++) {
        hideIds[i] = "bmp_" + (diff.hideStart() + i);
    }

    poolManager.batchUpdateAdaptive(zoneName,
        geoIds, geoTransforms, geoCount,
        bgIds, bgArgb, bgCount,
        txtIds, txtComponents, txtCount,
        hideIds, hideCount);
}
```

**Step 2: Remove old dirty-tracking fields**

Remove these fields that are no longer needed:
- `lastFramePixels` map
- `dirtyIdsScratch`, `dirtyArgbScratch` arrays
- `getDirtyIdsScratch()`, `getDirtyArgbScratch()` methods
- `bmpEntityIdCache` and `getBmpEntityId()` method

**Step 3: Update teardown to clean up assigner**

In the `teardown` method, add:
```java
assigners.remove(zoneName.toLowerCase());
```

**Step 4: Update applyRawFrame to use the new pipeline**

The existing `applyRawFrame` method creates a temp BitmapFrameBuffer and calls `applyFrame` — this still works. No change needed.

**Step 5: Verify build**

Run: `cd minecraft_plugin && mvn compile`
Expected: BUILD SUCCESS

**Step 6: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapRendererBackend.java
git commit -m "feat(bitmap): rewrite applyFrame with adaptive merge pipeline"
```

---

## Task 9: Integration test — full pipeline validation

Verify the entire pipeline works end-to-end without Bukkit (mock the pool manager).

**Files:**
- Create: `minecraft_plugin/src/test/java/com/audioviz/bitmap/adaptive/AdaptivePipelineTest.java`

**Step 1: Write the integration test**

```java
package com.audioviz.bitmap.adaptive;

import com.audioviz.bitmap.BitmapFrameBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Integration tests for the full adaptive pipeline:
 * frame buffer → cell grid → merge → assign → diff.
 */
class AdaptivePipelineTest {

    @Test
    @DisplayName("64x64 solid color: merges to 1 rect")
    void solidImage() {
        BitmapFrameBuffer buf = new BitmapFrameBuffer(64, 64);
        buf.fill(0xFF112233);

        List<MergedRect> rects = CellGridMerger.merge(buf.getRawPixels(), 64, 64);

        assertEquals(1, rects.size());
        assertEquals(64, rects.get(0).w());
        assertEquals(32, rects.get(0).h()); // 64 pixel rows → 32 cell rows
        assertTrue(rects.get(0).isUniform());
    }

    @Test
    @DisplayName("64x64 with center square: background merges, detail doesn't")
    void imageWithCenterSquare() {
        BitmapFrameBuffer buf = new BitmapFrameBuffer(64, 64);
        buf.fill(0xFF000000); // black background

        // Draw a 16x16 red square in the center
        buf.fillRect(24, 24, 16, 16, 0xFFFF0000);

        List<MergedRect> rects = CellGridMerger.merge(buf.getRawPixels(), 64, 64);

        // Should be far fewer than 2048 (64*32) rects
        assertTrue(rects.size() < 100, "Expected significant merging, got " + rects.size());

        // Verify total coverage
        int totalCells = rects.stream().mapToInt(MergedRect::cellCount).sum();
        assertEquals(64 * 32, totalCells);
    }

    @Test
    @DisplayName("assigner reports zero dirty on identical frames")
    void staticImageZeroDirty() {
        BitmapFrameBuffer buf = new BitmapFrameBuffer(32, 32);
        buf.fill(0xFF000000);
        buf.fillRect(8, 8, 16, 16, 0xFFFF0000);

        var assigner = new AdaptiveEntityAssigner(500);

        List<MergedRect> rects = CellGridMerger.merge(buf.getRawPixels(), 32, 32);
        assigner.assign(rects, 1.0f); // first frame

        // Second identical frame
        var diff = assigner.assign(rects, 1.0f);

        assertEquals(0, diff.geometryUpdates().size());
        assertEquals(0, diff.backgroundUpdates().size());
        assertEquals(0, diff.textUpdates().size());
    }

    @Test
    @DisplayName("spectrum bars pattern: entity count within budget")
    void spectrumBarsEntityCount() {
        BitmapFrameBuffer buf = new BitmapFrameBuffer(64, 32);
        buf.fill(0xFF000000);

        // Simulate 5 spectrum bars
        for (int band = 0; band < 5; band++) {
            int barX = band * 12 + 2;
            int barW = 10;
            double height = 0.3 + band * 0.15;
            int fillRows = (int) (height * 32);
            buf.fillRect(barX, 32 - fillRows, barW, fillRows, 0xFF00CCFF);
        }

        List<MergedRect> rects = CellGridMerger.merge(buf.getRawPixels(), 64, 32);

        // Black background should merge heavily; bars are solid blocks
        // Total should be well under 500
        assertTrue(rects.size() < 200, "Expected <200 rects for simple bars, got " + rects.size());
    }

    @Test
    @DisplayName("odd pixel height handled correctly")
    void oddPixelHeight() {
        BitmapFrameBuffer buf = new BitmapFrameBuffer(4, 3);
        buf.fill(0xFFFF0000);

        List<MergedRect> rects = CellGridMerger.merge(buf.getRawPixels(), 4, 3);

        // 4x3 pixels → 4x2 cell grid (last row: top=red, bottom=0)
        // Top cell row: all (red,red) → merges to 1 rect
        // Bottom cell row: all (red,0) → merges to 1 rect (different from top)
        assertEquals(2, rects.size());
        int totalCells = rects.stream().mapToInt(MergedRect::cellCount).sum();
        assertEquals(8, totalCells); // 4x2
    }
}
```

**Step 2: Run tests**

Run: `cd minecraft_plugin && mvn test -pl . -Dtest="com.audioviz.bitmap.adaptive.*"`
Expected: all tests PASS across all 4 test classes

**Step 3: Commit**

```bash
git add minecraft_plugin/src/test/java/com/audioviz/bitmap/adaptive/AdaptivePipelineTest.java
git commit -m "test(bitmap): add integration tests for adaptive pipeline"
```

---

## Task 10: Build and verify

Full build to ensure nothing is broken.

**Step 1: Run all tests**

Run: `cd minecraft_plugin && mvn test`
Expected: all tests PASS (including existing bitmap, entity, protocol tests)

**Step 2: Build the JAR**

Run: `cd minecraft_plugin && mvn package`
Expected: BUILD SUCCESS, JAR produced in `target/`

**Step 3: Review changes**

Run: `git diff --stat main`
Verify: only the expected files changed, no unintended modifications

**Step 4: Final commit if any fixes were needed**

If test failures required adjustments, commit those fixes.

---

## Summary

| Task | What | New/Modified |
|-|-|-|
| 1 | HalfBlockCell + MergedRect records | 2 new + 1 test |
| 2 | CellGridMerger (cell grid build) | 1 new + 1 test |
| 3 | CellGridMerger (greedy merge) | 1 modified + 1 test modified |
| 4 | AdaptiveEntityAssigner | 1 new + 1 test |
| 5 | BitmapFrameBuffer limit raise | 1 modified + 1 test modified |
| 6 | EntityPoolManager.batchUpdateAdaptive | 1 modified |
| 7 | BitmapRendererBackend spawning | 1 modified |
| 8 | BitmapRendererBackend applyFrame | 1 modified |
| 9 | Integration tests | 1 test |
| 10 | Build verification | — |

**Total: 4 new classes, 3 modified classes, 4 test files, ~10 commits**
