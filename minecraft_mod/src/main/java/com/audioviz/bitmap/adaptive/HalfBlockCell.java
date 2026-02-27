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
