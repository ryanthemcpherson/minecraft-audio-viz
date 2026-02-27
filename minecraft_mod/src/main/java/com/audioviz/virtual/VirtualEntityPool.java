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
    private BlockState defaultBlock = Blocks.WHITE_CONCRETE.getDefaultState();

    public VirtualEntityPool(int initialSize) {
        this.holder = new ElementHolder();
        this.elements = new ArrayList<>(initialSize);
        for (int i = 0; i < initialSize; i++) {
            addElement();
        }
    }

    private BlockDisplayElement addElement() {
        BlockDisplayElement el = new BlockDisplayElement(defaultBlock);
        el.setScale(new Vector3f(0.2f, 0.2f, 0.2f));
        holder.addElement(el);
        elements.add(el);
        return el;
    }

    public void resize(int newSize) {
        while (elements.size() < newSize) {
            addElement();
        }
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
        }
    }

    public record EntityUpdate(int index, Vec3d position, Vector3f scale, BlockState blockState) {}
}
