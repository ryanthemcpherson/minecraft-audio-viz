package com.audioviz.bitmap.adaptive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

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
            int[] pixels = {
                RED,   GREEN,
                BLUE,  BLACK,
                BLACK, RED,
                GREEN, BLUE,
            };
            HalfBlockCell[] cells = CellGridMerger.buildCellGrid(pixels, 2, 4);
            assertEquals(4, cells.length);
            assertEquals(new HalfBlockCell(RED, BLUE), cells[0]);
            assertEquals(new HalfBlockCell(GREEN, BLACK), cells[1]);
            assertEquals(new HalfBlockCell(BLACK, GREEN), cells[2]);
            assertEquals(new HalfBlockCell(RED, BLUE), cells[3]);
        }

        @Test
        @DisplayName("odd height: last row gets transparent bottom")
        void oddHeight() {
            int[] pixels = {
                RED,   GREEN,
                BLUE,  BLACK,
                RED,   RED,
            };
            HalfBlockCell[] cells = CellGridMerger.buildCellGrid(pixels, 2, 3);
            assertEquals(4, cells.length);
            assertEquals(new HalfBlockCell(RED, 0), cells[2]);
            assertEquals(new HalfBlockCell(RED, 0), cells[3]);
        }

        @Test
        @DisplayName("cell grid dimensions")
        void dimensions() {
            assertEquals(2, CellGridMerger.cellGridHeight(4));
            assertEquals(2, CellGridMerger.cellGridHeight(3));
            assertEquals(1, CellGridMerger.cellGridHeight(1));
            assertEquals(1, CellGridMerger.cellGridHeight(2));
        }
    }

    @Nested
    @DisplayName("Greedy rectangle merge")
    class GreedyMerge {

        @Test
        @DisplayName("solid color merges into one rect")
        void solidColor() {
            int[] pixels = new int[16];
            java.util.Arrays.fill(pixels, BLACK);
            List<MergedRect> rects = CellGridMerger.merge(pixels, 4, 4);
            assertEquals(1, rects.size());
            assertEquals(4, rects.get(0).w());
            assertEquals(2, rects.get(0).h());
        }

        @Test
        @DisplayName("all unique cells: one rect per cell")
        void allUnique() {
            int[] pixels = { RED, GREEN, BLUE, BLACK };
            List<MergedRect> rects = CellGridMerger.merge(pixels, 2, 2);
            assertEquals(2, rects.size());
        }

        @Test
        @DisplayName("horizontal run merges adjacent identical cells")
        void horizontalRun() {
            int[] pixels = { RED, RED, RED, RED, RED, RED, RED, RED };
            List<MergedRect> rects = CellGridMerger.merge(pixels, 4, 2);
            assertEquals(1, rects.size());
            assertEquals(4, rects.get(0).w());
            assertEquals(1, rects.get(0).h());
        }

        @Test
        @DisplayName("vertical run merges identical cell rows")
        void verticalRun() {
            int[] pixels = { RED, RED, RED, RED };
            List<MergedRect> rects = CellGridMerger.merge(pixels, 1, 4);
            assertEquals(1, rects.size());
            assertEquals(1, rects.get(0).w());
            assertEquals(2, rects.get(0).h());
        }

        @Test
        @DisplayName("L-shaped region produces multiple rects")
        void lShape() {
            int[] pixels = {
                RED,  RED,
                RED,  RED,
                RED,  BLUE,
                BLUE, BLUE,
            };
            List<MergedRect> rects = CellGridMerger.merge(pixels, 2, 4);
            assertEquals(3, rects.size());
            int totalCells = rects.stream().mapToInt(MergedRect::cellCount).sum();
            assertEquals(4, totalCells);
        }

        @Test
        @DisplayName("merge output covers all cells exactly once")
        void fullCoverage() {
            int[] pixels = {
                RED,   GREEN, RED,   GREEN,
                BLUE,  BLACK, BLUE,  BLACK,
                GREEN, RED,   GREEN, RED,
                BLACK, BLUE,  BLACK, BLUE,
            };
            List<MergedRect> rects = CellGridMerger.merge(pixels, 4, 4);
            int totalCells = rects.stream().mapToInt(MergedRect::cellCount).sum();
            assertEquals(8, totalCells);
        }

        @Test
        @DisplayName("half-block cells with same top but different bottom don't merge")
        void differentBottomNoMerge() {
            int[] pixels = { RED, RED, GREEN, BLUE };
            List<MergedRect> rects = CellGridMerger.merge(pixels, 2, 2);
            assertEquals(2, rects.size());
        }
    }

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
        void checkerboardNoHorizontalMerging() {
            // Alternating colors at pixel level — adjacent cells differ so no horizontal merges.
            // However, cell columns repeat vertically (rows 0-1 and 2-3 produce identical cells),
            // so the greedy algorithm merges each column down into 1x2 rects.
            int a = 0xFFFF0000, b = 0xFF00FF00;
            int[] pixels = {
                a, b, a, b,
                b, a, b, a,
                a, b, a, b,
                b, a, b, a,
            };
            var rects = CellGridMerger.merge(pixels, 4, 4);
            // 4 columns, each merged vertically into 1x2 rect
            assertEquals(4, rects.size(), "Checkerboard should produce 4 rects (one per column, merged vertically)");
            for (var r : rects) {
                assertEquals(1, r.w(), "Each rect should be 1 column wide");
                assertEquals(2, r.h(), "Each rect should span both cell rows");
            }
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
}
