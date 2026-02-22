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
            // bgDirty tracks only the top color (entity background). Bottom color is
            // conveyed via TextUpdate (the ▄ character's text color).
            boolean bgDirty = !wasActive || rect.topARGB() != lastTopARGB[i];
            boolean bottomDirty = !wasActive || rect.bottomARGB() != lastBottomARGB[i];

            // Track uniform state transitions to manage text vs space
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
