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
}
