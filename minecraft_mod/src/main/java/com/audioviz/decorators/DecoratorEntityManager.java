package com.audioviz.decorators;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.DisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.TextDisplayElement;
import net.minecraft.block.BlockState;
import net.minecraft.entity.decoration.Brightness;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Polymer virtual display entities for stage decorators.
 * Supports both TextDisplayElement and BlockDisplayElement pools.
 *
 * <p>Each decorator zone gets its own ElementHolder attached to the
 * world at the zone origin. Element positions are relative to the origin.
 */
public class DecoratorEntityManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz");

    private final Map<String, DecoratorPool> pools = new ConcurrentHashMap<>();

    private static class DecoratorPool {
        final ElementHolder holder;
        final List<DisplayElement> elements = new ArrayList<>();
        final String type; // "text" or "block"
        final Vec3d origin;

        DecoratorPool(String type, Vec3d origin) {
            this.holder = new ElementHolder();
            this.type = type;
            this.origin = origin;
        }
    }

    // ========== Pool Initialization ==========

    public void initTextPool(String zoneName, int count, ServerWorld world, Vec3d position) {
        initTextPool(zoneName, count, DisplayEntity.BillboardMode.CENTER, world, position);
    }

    public void initTextPool(String zoneName, int count,
                              DisplayEntity.BillboardMode billboard,
                              ServerWorld world, Vec3d position) {
        cleanup(zoneName);
        DecoratorPool pool = new DecoratorPool("text", position);

        for (int i = 0; i < count; i++) {
            TextDisplayElement el = new TextDisplayElement();
            el.setText(Text.empty());
            el.setBillboardMode(billboard);
            el.setScale(new Vector3f(1f, 1f, 1f));
            el.setInterpolationDuration(2);
            pool.holder.addElement(el);
            pool.elements.add(el);
        }

        ChunkAttachment.of(pool.holder, world, position);
        pools.put(zoneName.toLowerCase(), pool);
        LOGGER.debug("Decorator text pool '{}': {} elements at {}", zoneName, count, position);
    }

    public void initBlockPool(String zoneName, int count, BlockState blockState,
                               ServerWorld world, Vec3d position) {
        cleanup(zoneName);
        DecoratorPool pool = new DecoratorPool("block", position);

        for (int i = 0; i < count; i++) {
            BlockDisplayElement el = new BlockDisplayElement(blockState);
            el.setScale(new Vector3f(0.9f, 0.9f, 0.9f));
            el.setInterpolationDuration(2);
            pool.holder.addElement(el);
            pool.elements.add(el);
        }

        ChunkAttachment.of(pool.holder, world, position);
        pools.put(zoneName.toLowerCase(), pool);
        LOGGER.debug("Decorator block pool '{}': {} elements at {}", zoneName, count, position);
    }

    // ========== Batch Updates ==========

    /**
     * Apply a list of DecoratorUpdates to the pool's elements.
     * Positions in updates are absolute world coordinates;
     * converted to holder-relative translations internally.
     */
    public void batchUpdate(String zoneName, List<DecoratorUpdate> updates) {
        DecoratorPool pool = pools.get(zoneName.toLowerCase());
        if (pool == null) return;

        for (DecoratorUpdate update : updates) {
            int idx = update.slotIndex();
            if (idx < 0 || idx >= pool.elements.size()) continue;

            DisplayElement el = pool.elements.get(idx);
            applyUpdate(el, update, pool.origin);
        }

        pool.holder.tick();
    }

    private void applyUpdate(DisplayElement el, DecoratorUpdate update, Vec3d origin) {
        // Position: convert absolute world pos to holder-relative translation
        if (update.position() != null) {
            Vector3f offset = new Vector3f(
                (float) (update.position().x - origin.x),
                (float) (update.position().y - origin.y),
                (float) (update.position().z - origin.z)
            );
            if (update.translation() != null) {
                offset.add(update.translation());
            }
            el.setTranslation(offset);
        } else if (update.translation() != null) {
            el.setTranslation(update.translation());
        }

        if (update.scale() != null) {
            el.setScale(update.scale());
        }

        if (update.leftRotation() != null) {
            el.setLeftRotation(update.leftRotation());
        }

        if (update.interpolationDuration() != null) {
            el.setInterpolationDuration(update.interpolationDuration());
        }

        if (update.brightness() != null) {
            el.setBrightness(new Brightness(update.brightness(), 15));
        }

        if (update.glow() != null) {
            el.setGlowing(update.glow());
        }

        // Start interpolation so transforms actually animate over interpolationDuration
        if (update.interpolationDuration() != null && update.interpolationDuration() > 0) {
            el.startInterpolation();
        }
    }

    // ========== Text-Specific Operations ==========

    public void updateTextContent(String zoneName, String entityId, String text) {
        DecoratorPool pool = pools.get(zoneName.toLowerCase());
        if (pool == null || !"text".equals(pool.type)) return;

        int idx = extractIndex(entityId);
        if (idx < 0 || idx >= pool.elements.size()) return;

        if (pool.elements.get(idx) instanceof TextDisplayElement textEl) {
            textEl.setText(Text.literal(text != null ? text : ""));
        }
    }

    public void updateTextBackground(String zoneName, String entityId, int argb) {
        DecoratorPool pool = pools.get(zoneName.toLowerCase());
        if (pool == null || !"text".equals(pool.type)) return;

        int idx = extractIndex(entityId);
        if (idx < 0 || idx >= pool.elements.size()) return;

        if (pool.elements.get(idx) instanceof TextDisplayElement textEl) {
            textEl.setBackground(argb);
        }
    }

    public void batchUpdateBackgrounds(String zoneName, Map<String, Integer> colorMap) {
        DecoratorPool pool = pools.get(zoneName.toLowerCase());
        if (pool == null || !"text".equals(pool.type)) return;

        for (Map.Entry<String, Integer> entry : colorMap.entrySet()) {
            int idx = extractIndex(entry.getKey());
            if (idx < 0 || idx >= pool.elements.size()) continue;

            if (pool.elements.get(idx) instanceof TextDisplayElement textEl) {
                textEl.setBackground(entry.getValue());
            }
        }

        pool.holder.tick();
    }

    // ========== Block-Specific Operations ==========

    public void updateBlockState(String zoneName, String entityId, BlockState blockState) {
        DecoratorPool pool = pools.get(zoneName.toLowerCase());
        if (pool == null || !"block".equals(pool.type)) return;

        int idx = extractIndex(entityId);
        if (idx < 0 || idx >= pool.elements.size()) return;

        if (pool.elements.get(idx) instanceof BlockDisplayElement blockEl) {
            blockEl.setBlockState(blockState);
        }
    }

    // ========== Zone-Wide Operations ==========

    public void setZoneBrightness(String zoneName, int brightness) {
        DecoratorPool pool = pools.get(zoneName.toLowerCase());
        if (pool == null) return;

        Brightness b = new Brightness(brightness, 15);
        for (DisplayElement el : pool.elements) {
            el.setBrightness(b);
        }
        pool.holder.tick();
    }

    public void setZoneGlow(String zoneName, boolean glow) {
        DecoratorPool pool = pools.get(zoneName.toLowerCase());
        if (pool == null) return;

        for (DisplayElement el : pool.elements) {
            el.setGlowing(glow);
        }
        pool.holder.tick();
    }

    // ========== Lifecycle ==========

    public void cleanup(String zoneName) {
        DecoratorPool pool = pools.remove(zoneName.toLowerCase());
        if (pool != null) {
            pool.holder.destroy();
        }
    }

    public void cleanupAll() {
        for (DecoratorPool pool : pools.values()) {
            pool.holder.destroy();
        }
        pools.clear();
    }

    public boolean hasPool(String zoneName) {
        return pools.containsKey(zoneName.toLowerCase());
    }

    // ========== Helpers ==========

    private static int extractIndex(String entityId) {
        int underscore = entityId.lastIndexOf('_');
        if (underscore < 0) return 0;
        try {
            return Integer.parseInt(entityId.substring(underscore + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
