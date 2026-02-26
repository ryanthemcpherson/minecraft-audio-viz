package com.audioviz.virtual;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Pool of virtual BlockDisplay elements managed by Polymer.
 * No server-side entities — pure packet-based rendering.
 * Zero collision, zero ticking, zero chunk tracking overhead.
 */
public class VirtualEntityPool {
    private final ElementHolder holder;
    private final List<BlockDisplayElement> elements;
    private BlockState defaultBlock;

    /** Interpolation duration in ticks (2 ticks = 100ms — smooth but responsive). */
    private static final int INTERPOLATION_TICKS = 2;

    public VirtualEntityPool(int initialSize, BlockState defaultBlock) {
        this.holder = new ElementHolder();
        this.defaultBlock = defaultBlock;
        this.elements = new ArrayList<>(initialSize);
        for (int i = 0; i < initialSize; i++) {
            addElement();
        }
    }

    public VirtualEntityPool(int initialSize) {
        this(initialSize, Blocks.WHITE_CONCRETE.getDefaultState());
    }

    /** Update the default block state and apply it to all existing elements. */
    public void setDefaultBlock(BlockState blockState) {
        this.defaultBlock = blockState;
        for (BlockDisplayElement el : elements) {
            el.setBlockState(blockState);
        }
    }

    private BlockDisplayElement addElement() {
        BlockDisplayElement el = new BlockDisplayElement(defaultBlock);
        el.setInterpolationDuration(INTERPOLATION_TICKS);
        holder.addElement(el);
        elements.add(el);
        return el;
    }

    public void resize(int newSize) {
        // Grow: new elements start at scale 0 (invisible) via addElement()
        while (elements.size() < newSize) {
            addElement();
        }
        // Shrink: remove excess elements from the holder
        while (elements.size() > newSize) {
            BlockDisplayElement removed = elements.remove(elements.size() - 1);
            holder.removeElement(removed);
        }
    }

    public BlockDisplayElement get(int index) {
        return elements.get(index);
    }

    public int size() {
        return elements.size();
    }

    public ElementHolder getHolder() {
        return holder;
    }

    /**
     * Batch-update all elements from EntityUpdate list.
     * Call holder.tick() after to flush changes.
     */
    public void applyUpdates(List<EntityUpdate> updates) {
        for (EntityUpdate update : updates) {
            int idx = update.index();
            if (idx < 0 || idx >= elements.size()) continue;
            BlockDisplayElement el = elements.get(idx);

            if (update.visible() != null && !update.visible()) {
                // Hide: set scale to zero
                el.setScale(new Vector3f(0f, 0f, 0f));
                el.startInterpolationIfDirty();
                continue;
            }

            if (update.position() != null) {
                el.setTranslation(new Vector3f(
                    (float) update.position().x,
                    (float) update.position().y,
                    (float) update.position().z));
            }
            if (update.scale() != null) {
                el.setScale(update.scale());
            }
            if (update.blockState() != null) {
                el.setBlockState(update.blockState());
            }
            el.startInterpolationIfDirty();
        }
    }

    /**
     * Hide all entities by setting scale to zero. Used on pattern swap
     * to prevent stale entities lingering at old positions.
     */
    public void hideAll() {
        Vector3f zero = new Vector3f(0f, 0f, 0f);
        for (BlockDisplayElement el : elements) {
            el.setScale(zero);
            el.startInterpolationIfDirty();
        }
    }

    public record EntityUpdate(int index, Vec3d position, Vector3f scale,
                                BlockState blockState, Boolean visible) {}
}
