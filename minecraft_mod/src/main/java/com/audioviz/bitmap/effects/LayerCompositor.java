package com.audioviz.bitmap.effects;

import com.audioviz.bitmap.BitmapFrameBuffer;

/**
 * Layer blend compositor — combines two frame buffers using standard
 * VJ blend modes. This enables running two patterns simultaneously
 * with visual mixing.
 *
 * <p>Supports the essential blend modes used in VJ software:
 * additive (bright highlights), multiply (shadows), screen (glow),
 * and overlay (contrast enhancement).
 */
public class LayerCompositor {

    public enum BlendMode {
        /** Normal alpha blend: top replaces bottom. */
        NORMAL,
        /** Additive: colors add together (good for glow/particles). */
        ADDITIVE,
        /** Multiply: colors multiply (dark areas get darker). */
        MULTIPLY,
        /** Screen: inverse multiply (brightens, good for overlays). */
        SCREEN,
        /** Overlay: multiply darks, screen lights (contrast boost). */
        OVERLAY,
        /** Max: takes the brighter channel of each pixel. */
        LIGHTEN,
        /** Min: takes the darker channel of each pixel. */
        DARKEN
    }

    private LayerCompositor() {}

    /**
     * Blend the top layer over the bottom layer into the output buffer.
     *
     * @param bottom  base layer
     * @param top     layer to blend over
     * @param output  destination buffer
     * @param mode    blend mode
     * @param opacity top layer opacity (0.0-1.0)
     */
    public static void blend(BitmapFrameBuffer bottom, BitmapFrameBuffer top,
                              BitmapFrameBuffer output, BlendMode mode, double opacity) {
        int[] bPx = bottom.getRawPixels();
        int[] tPx = top.getRawPixels();
        int[] oPx = output.getRawPixels();
        int len = Math.min(oPx.length, Math.min(bPx.length, tPx.length));
        float op = (float) Math.max(0, Math.min(1, opacity));

        for (int i = 0; i < len; i++) {
            int b = bPx[i];
            int t = tPx[i];

            int blended = switch (mode) {
                case NORMAL   -> t;
                case ADDITIVE -> blendAdditive(b, t);
                case MULTIPLY -> blendMultiply(b, t);
                case SCREEN   -> blendScreen(b, t);
                case OVERLAY  -> blendOverlay(b, t);
                case LIGHTEN  -> blendLighten(b, t);
                case DARKEN   -> blendDarken(b, t);
            };

            // Apply opacity
            if (op < 1.0f) {
                oPx[i] = BitmapFrameBuffer.lerpColor(b, blended, op);
            } else {
                oPx[i] = blended;
            }
        }
    }

    /**
     * Blend top directly over bottom in-place (bottom is modified).
     */
    public static void blendInPlace(BitmapFrameBuffer bottom, BitmapFrameBuffer top,
                                     BlendMode mode, double opacity) {
        blend(bottom, top, bottom, mode, opacity);
    }

    // ========== Blend Mode Implementations ==========

    private static int blendAdditive(int b, int t) {
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int tr = (t >> 16) & 0xFF, tg = (t >> 8) & 0xFF, tb = t & 0xFF;
        return BitmapFrameBuffer.packARGB(255,
            Math.min(255, br + tr),
            Math.min(255, bg + tg),
            Math.min(255, bb + tb));
    }

    private static int blendMultiply(int b, int t) {
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int tr = (t >> 16) & 0xFF, tg = (t >> 8) & 0xFF, tb = t & 0xFF;
        return BitmapFrameBuffer.packARGB(255,
            (br * tr) / 255,
            (bg * tg) / 255,
            (bb * tb) / 255);
    }

    private static int blendScreen(int b, int t) {
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int tr = (t >> 16) & 0xFF, tg = (t >> 8) & 0xFF, tb = t & 0xFF;
        return BitmapFrameBuffer.packARGB(255,
            255 - ((255 - br) * (255 - tr)) / 255,
            255 - ((255 - bg) * (255 - tg)) / 255,
            255 - ((255 - bb) * (255 - tb)) / 255);
    }

    private static int blendOverlay(int b, int t) {
        return BitmapFrameBuffer.packARGB(255,
            overlayChannel((b >> 16) & 0xFF, (t >> 16) & 0xFF),
            overlayChannel((b >> 8) & 0xFF, (t >> 8) & 0xFF),
            overlayChannel(b & 0xFF, t & 0xFF));
    }

    private static int overlayChannel(int base, int top) {
        if (base < 128) {
            return (2 * base * top) / 255;
        } else {
            return 255 - (2 * (255 - base) * (255 - top)) / 255;
        }
    }

    private static int blendLighten(int b, int t) {
        return BitmapFrameBuffer.packARGB(255,
            Math.max((b >> 16) & 0xFF, (t >> 16) & 0xFF),
            Math.max((b >> 8) & 0xFF, (t >> 8) & 0xFF),
            Math.max(b & 0xFF, t & 0xFF));
    }

    private static int blendDarken(int b, int t) {
        return BitmapFrameBuffer.packARGB(255,
            Math.min((b >> 16) & 0xFF, (t >> 16) & 0xFF),
            Math.min((b >> 8) & 0xFF, (t >> 8) & 0xFF),
            Math.min(b & 0xFF, t & 0xFF));
    }
}
