package com.audioviz.bitmap.text;

import com.audioviz.bitmap.BitmapFrameBuffer;

/**
 * Renders text into a {@link BitmapFrameBuffer} using the built-in
 * {@link BitmapFont}. Supports color, horizontal offset (for scrolling),
 * and vertical alignment.
 *
 * <p>This is the core text primitive — patterns like marquee and track
 * display compose on top of this.
 */
public class BitmapTextRenderer {

    public enum VAlign { TOP, CENTER, BOTTOM }

    private BitmapTextRenderer() {}

    /**
     * Draw a string into the buffer at a pixel-level offset.
     *
     * @param buffer  target frame buffer
     * @param text    string to render
     * @param offsetX horizontal pixel offset (negative = scrolled left)
     * @param offsetY vertical pixel offset from top
     * @param color   ARGB color for text pixels
     */
    public static void drawText(BitmapFrameBuffer buffer, String text,
                                int offsetX, int offsetY, int color) {
        if (text == null || text.isEmpty()) return;

        int charStride = BitmapFont.CHAR_WIDTH + BitmapFont.CHAR_SPACING;

        for (int ci = 0; ci < text.length(); ci++) {
            char c = text.charAt(ci);
            byte[] glyph = BitmapFont.getGlyph(c);
            int baseX = offsetX + ci * charStride;

            // Skip if entirely off-screen
            if (baseX + BitmapFont.CHAR_WIDTH < 0) continue;
            if (baseX >= buffer.getWidth()) break;

            for (int gy = 0; gy < BitmapFont.CHAR_HEIGHT; gy++) {
                int py = offsetY + gy;
                if (py < 0 || py >= buffer.getHeight()) continue;

                for (int gx = 0; gx < BitmapFont.CHAR_WIDTH; gx++) {
                    if (BitmapFont.isPixelSet(glyph, gx, gy)) {
                        int px = baseX + gx;
                        if (px >= 0 && px < buffer.getWidth()) {
                            buffer.setPixel(px, py, color);
                        }
                    }
                }
            }
        }
    }

    /**
     * Draw centered text.
     */
    public static void drawTextCentered(BitmapFrameBuffer buffer, String text,
                                        int color) {
        drawTextCentered(buffer, text, color, VAlign.CENTER);
    }

    /**
     * Draw text centered horizontally with vertical alignment.
     */
    public static void drawTextCentered(BitmapFrameBuffer buffer, String text,
                                        int color, VAlign vAlign) {
        if (text == null || text.isEmpty()) return;

        int textWidth = BitmapFont.measureString(text);
        int offsetX = (buffer.getWidth() - textWidth) / 2;

        int offsetY = switch (vAlign) {
            case TOP    -> 1;
            case CENTER -> (buffer.getHeight() - BitmapFont.CHAR_HEIGHT) / 2;
            case BOTTOM -> buffer.getHeight() - BitmapFont.CHAR_HEIGHT - 1;
        };

        drawText(buffer, text, offsetX, offsetY, color);
    }

    /**
     * Draw text with a shadow/outline for better readability over busy backgrounds.
     */
    public static void drawTextWithShadow(BitmapFrameBuffer buffer, String text,
                                          int offsetX, int offsetY,
                                          int textColor, int shadowColor) {
        // Shadow (1px offset down-right)
        drawText(buffer, text, offsetX + 1, offsetY + 1, shadowColor);
        // Foreground
        drawText(buffer, text, offsetX, offsetY, textColor);
    }

    /**
     * Draw text with an outline (all 8 adjacent pixels).
     */
    public static void drawTextOutlined(BitmapFrameBuffer buffer, String text,
                                        int offsetX, int offsetY,
                                        int textColor, int outlineColor) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                drawText(buffer, text, offsetX + dx, offsetY + dy, outlineColor);
            }
        }
        drawText(buffer, text, offsetX, offsetY, textColor);
    }

    /**
     * Draw two lines of text (title + subtitle), centered.
     * Commonly used for track artist/title display.
     */
    public static void drawTwoLines(BitmapFrameBuffer buffer,
                                    String line1, int color1,
                                    String line2, int color2) {
        int totalHeight = BitmapFont.CHAR_HEIGHT * 2 + 2; // 2px gap
        int startY = (buffer.getHeight() - totalHeight) / 2;

        int w1 = BitmapFont.measureString(line1);
        int w2 = BitmapFont.measureString(line2);

        drawText(buffer, line1, (buffer.getWidth() - w1) / 2, startY, color1);
        drawText(buffer, line2, (buffer.getWidth() - w2) / 2,
                 startY + BitmapFont.CHAR_HEIGHT + 2, color2);
    }
}
