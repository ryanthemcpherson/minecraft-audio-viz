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
