package com.audioviz.bitmap.adaptive;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CellGridMergerTest {

    // ========== cellGridHeight ==========

    @Nested
    class CellGridHeightTests {

        @Test
        void evenPixelHeight() {
            assertEquals(3, CellGridMerger.cellGridHeight(6));
        }

        @Test
        void oddPixelHeight() {
            // 7 pixels -> ceil(7/2) = 4 cell rows
            assertEquals(4, CellGridMerger.cellGridHeight(7));
        }

        @Test
        void singlePixelHeight() {
            assertEquals(1, CellGridMerger.cellGridHeight(1));
        }

        @Test
        void twoPixelHeight() {
            assertEquals(1, CellGridMerger.cellGridHeight(2));
        }

        @Test
        void zeroPixelHeight() {
            // (0 + 1) / 2 = 0 (integer division)
            assertEquals(0, CellGridMerger.cellGridHeight(0));
        }
    }

    // ========== buildCellGrid ==========

    @Nested
    class BuildCellGridTests {

        @Test
        void uniformColorProducesCellsWithMatchingColors() {
            int w = 2, h = 2;
            int color = 0xFFAABBCC;
            int[] pixels = { color, color, color, color };

            HalfBlockCell[] cells = CellGridMerger.buildCellGrid(pixels, w, h);

            assertEquals(2, cells.length); // 2 wide, 1 cell row (2 pixel rows -> 1 cell row)
            for (HalfBlockCell cell : cells) {
                assertEquals(color, cell.topARGB());
                assertEquals(color, cell.bottomARGB());
                assertTrue(cell.isUniform());
            }
        }

        @Test
        void oddHeightLastRowBottomIsZero() {
            int w = 2, h = 3;
            int color = 0xFF112233;
            // 3 rows of 2 pixels; cell grid height = 2
            int[] pixels = { color, color, color, color, color, color };

            HalfBlockCell[] cells = CellGridMerger.buildCellGrid(pixels, w, h);

            // 2 wide x 2 cell rows = 4 cells
            assertEquals(4, cells.length);
            // First cell row: top=row0, bottom=row1
            assertEquals(color, cells[0].topARGB());
            assertEquals(color, cells[0].bottomARGB());
            // Second cell row: top=row2, bottom=0 (no row3)
            assertEquals(color, cells[2].topARGB());
            assertEquals(0, cells[2].bottomARGB());
        }

        @Test
        void differentTopAndBottomColors() {
            int w = 1, h = 2;
            int top = 0xFFFF0000;
            int bottom = 0xFF00FF00;
            int[] pixels = { top, bottom };

            HalfBlockCell[] cells = CellGridMerger.buildCellGrid(pixels, w, h);

            assertEquals(1, cells.length);
            assertEquals(top, cells[0].topARGB());
            assertEquals(bottom, cells[0].bottomARGB());
            assertFalse(cells[0].isUniform());
        }
    }

    // ========== buildCellGridInto ==========

    @Nested
    class BuildCellGridIntoTests {

        @Test
        void matchesObjectBasedVersion() {
            int w = 3, h = 4;
            int[] pixels = new int[w * h];
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = 0xFF000000 | (i * 17);
            }

            // Object-based
            HalfBlockCell[] cells = CellGridMerger.buildCellGrid(pixels, w, h);

            // Parallel-array based
            int cellHeight = CellGridMerger.cellGridHeight(h);
            int cellCount = w * cellHeight;
            int[] topARGB = new int[cellCount];
            int[] bottomARGB = new int[cellCount];
            CellGridMerger.buildCellGridInto(pixels, w, h, topARGB, bottomARGB);

            for (int i = 0; i < cellCount; i++) {
                assertEquals(cells[i].topARGB(), topARGB[i],
                    "Top mismatch at cell " + i);
                assertEquals(cells[i].bottomARGB(), bottomARGB[i],
                    "Bottom mismatch at cell " + i);
            }
        }

        @Test
        void oddHeightMatchesObjectVersion() {
            int w = 2, h = 5;
            int[] pixels = new int[w * h];
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = 0xFF000000 | (i * 31);
            }

            HalfBlockCell[] cells = CellGridMerger.buildCellGrid(pixels, w, h);

            int cellHeight = CellGridMerger.cellGridHeight(h);
            int cellCount = w * cellHeight;
            int[] topARGB = new int[cellCount];
            int[] bottomARGB = new int[cellCount];
            CellGridMerger.buildCellGridInto(pixels, w, h, topARGB, bottomARGB);

            for (int i = 0; i < cellCount; i++) {
                assertEquals(cells[i].topARGB(), topARGB[i]);
                assertEquals(cells[i].bottomARGB(), bottomARGB[i]);
            }
        }
    }

    // ========== merge ==========

    @Nested
    class MergeTests {

        @Test
        void uniformRegionMergesToSingleRect() {
            int w = 4, h = 6;
            int color = 0xFF00FF00;
            int[] pixels = new int[w * h];
            java.util.Arrays.fill(pixels, color);

            List<MergedRect> rects = CellGridMerger.merge(pixels, w, h);

            // All cells identical -> single rect covering entire grid
            assertEquals(1, rects.size());
            MergedRect rect = rects.get(0);
            assertEquals(0, rect.x());
            assertEquals(0, rect.y());
            assertEquals(w, rect.w());
            assertEquals(CellGridMerger.cellGridHeight(h), rect.h());
            assertEquals(color, rect.topARGB());
            assertEquals(color, rect.bottomARGB());
        }

        @Test
        void allDifferentCellsProduceOneRectPerCell() {
            int w = 2, h = 2;
            // Each pixel is unique so each cell has unique top/bottom
            int[] pixels = { 0xFF110000, 0xFF220000, 0xFF330000, 0xFF440000 };

            List<MergedRect> rects = CellGridMerger.merge(pixels, w, h);

            // 2 wide x 1 cell row = 2 cells total
            int cellCount = w * CellGridMerger.cellGridHeight(h);
            // Each cell has a unique top pixel, but the merge considers (top, bottom) pairs.
            // Cell (0,0): top=0xFF110000, bottom=0xFF330000
            // Cell (1,0): top=0xFF220000, bottom=0xFF440000
            // These differ, so we get 2 rects
            assertEquals(cellCount, rects.size());
        }

        @Test
        void mergeCoversAllCellsExactlyOnce() {
            int w = 4, h = 4;
            int[] pixels = new int[w * h];
            // Create a checkerboard pattern at cell level
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    pixels[y * w + x] = ((x + y) % 2 == 0) ? 0xFFFF0000 : 0xFF0000FF;
                }
            }

            List<MergedRect> rects = CellGridMerger.merge(pixels, w, h);

            // Verify total cell coverage
            int totalCells = 0;
            for (MergedRect rect : rects) {
                totalCells += rect.cellCount();
            }
            int expectedCells = w * CellGridMerger.cellGridHeight(h);
            assertEquals(expectedCells, totalCells);
        }

        @Test
        void singlePixelBufferProducesOneRect() {
            int w = 1, h = 1;
            int[] pixels = { 0xFFAABBCC };

            List<MergedRect> rects = CellGridMerger.merge(pixels, w, h);

            assertEquals(1, rects.size());
            MergedRect rect = rects.get(0);
            assertEquals(0, rect.x());
            assertEquals(0, rect.y());
            assertEquals(1, rect.w());
            assertEquals(1, rect.h());
            assertEquals(0xFFAABBCC, rect.topARGB());
            assertEquals(0, rect.bottomARGB()); // odd height -> no bottom row
        }

        @Test
        void horizontalStripeMergesIntoRows() {
            int w = 4, h = 4;
            int[] pixels = new int[w * h];
            // Row 0-1: red, Row 2-3: blue
            // This means cell row 0 has top=red, bottom=red
            // Cell row 1 has top=blue, bottom=blue
            for (int x = 0; x < w; x++) {
                pixels[0 * w + x] = 0xFFFF0000;
                pixels[1 * w + x] = 0xFFFF0000;
                pixels[2 * w + x] = 0xFF0000FF;
                pixels[3 * w + x] = 0xFF0000FF;
            }

            List<MergedRect> rects = CellGridMerger.merge(pixels, w, h);

            // Two cell rows with different colors -> 2 rects, each spanning full width
            assertEquals(2, rects.size());
            assertEquals(w, rects.get(0).w());
            assertEquals(w, rects.get(1).w());
        }
    }

    // ========== asCellRects ==========

    @Nested
    class AsCellRectsTests {

        @Test
        void producesOneRectPerCell() {
            int w = 3, h = 4;
            int[] pixels = new int[w * h];
            java.util.Arrays.fill(pixels, 0xFFAAAAAA);

            List<MergedRect> rects = CellGridMerger.asCellRects(pixels, w, h);

            int expectedCells = w * CellGridMerger.cellGridHeight(h);
            assertEquals(expectedCells, rects.size());
            for (MergedRect rect : rects) {
                assertEquals(1, rect.w());
                assertEquals(1, rect.h());
            }
        }

        @Test
        void cellRectsAreInRowMajorOrder() {
            int w = 2, h = 2;
            int[] pixels = new int[w * h];
            java.util.Arrays.fill(pixels, 0xFF000000);

            List<MergedRect> rects = CellGridMerger.asCellRects(pixels, w, h);

            // 2 wide x 1 cell row -> 2 rects: (0,0) then (1,0)
            assertEquals(2, rects.size());
            assertEquals(0, rects.get(0).x());
            assertEquals(0, rects.get(0).y());
            assertEquals(1, rects.get(1).x());
            assertEquals(0, rects.get(1).y());
        }
    }

    // ========== MergedRect record ==========

    @Nested
    class MergedRectTests {

        @Test
        void cellCountIsWidthTimesHeight() {
            MergedRect rect = new MergedRect(0, 0, 3, 4, 0, 0);
            assertEquals(12, rect.cellCount());
        }

        @Test
        void isUniformWhenColorsMatch() {
            MergedRect uniform = new MergedRect(0, 0, 1, 1, 0xFFAA, 0xFFAA);
            assertTrue(uniform.isUniform());

            MergedRect nonUniform = new MergedRect(0, 0, 1, 1, 0xFFAA, 0xFFBB);
            assertFalse(nonUniform.isUniform());
        }
    }
}
