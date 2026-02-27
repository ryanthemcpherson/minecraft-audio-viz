package com.audioviz.bitmap.adaptive;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a BitmapFrameBuffer's pixel array into a half-block cell grid
 * and merges uniform rectangular regions using a greedy algorithm.
 *
 * <p>Stateless — all methods are static. Reusable across frames.
 */
public final class CellGridMerger {

    private CellGridMerger() {}

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

        List<MergedRect> result = new ArrayList<>();

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

    /**
     * Build one 1x1 rectangle per cell with no cross-cell merging.
     * Useful when visual fidelity/stability is preferred over entity-count reduction.
     *
     * @param pixels      row-major ARGB pixel array
     * @param pixelWidth  logical pixel width
     * @param pixelHeight logical pixel height
     * @return list of 1x1 rects in deterministic row-major order
     */
    public static List<MergedRect> asCellRects(int[] pixels, int pixelWidth, int pixelHeight) {
        int cellWidth = pixelWidth;
        int cellHeight = cellGridHeight(pixelHeight);
        HalfBlockCell[] cells = buildCellGrid(pixels, pixelWidth, pixelHeight);
        List<MergedRect> result = new ArrayList<>(cells.length);

        for (int cy = 0; cy < cellHeight; cy++) {
            for (int cx = 0; cx < cellWidth; cx++) {
                HalfBlockCell cell = cells[cy * cellWidth + cx];
                result.add(new MergedRect(cx, cy, 1, 1, cell.topARGB(), cell.bottomARGB()));
            }
        }
        return result;
    }
}
