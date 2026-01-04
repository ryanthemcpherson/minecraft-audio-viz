package com.audioviz.entities;

import com.audioviz.AudioVizPlugin;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages pools of Display Entities for efficient visualization.
 * Pre-spawns entities and moves them rather than spawn/despawn for performance.
 */
public class EntityPoolManager {

    private final AudioVizPlugin plugin;

    // Map: zoneName -> entityId -> Entity
    private final Map<String, Map<String, Entity>> entityPools;

    // Track entity types for each entity
    private final Map<UUID, EntityType> entityTypes;

    public enum EntityType {
        BLOCK_DISPLAY,
        ITEM_DISPLAY,
        TEXT_DISPLAY
    }

    public EntityPoolManager(AudioVizPlugin plugin) {
        this.plugin = plugin;
        this.entityPools = new ConcurrentHashMap<>();
        this.entityTypes = new ConcurrentHashMap<>();
    }

    /**
     * Initialize a pool of block display entities for a zone.
     */
    public void initializeBlockPool(String zoneName, int count, Material material) {
        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
        if (zone == null) {
            plugin.getLogger().warning("Cannot initialize pool: zone '" + zoneName + "' not found");
            return;
        }

        Map<String, Entity> pool = entityPools.computeIfAbsent(zoneName.toLowerCase(), k -> new ConcurrentHashMap<>());

        // Spawn entities on the main thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            Location spawnLoc = zone.getOrigin().clone();

            for (int i = 0; i < count; i++) {
                String entityId = "block_" + i;

                // Skip if already exists
                if (pool.containsKey(entityId)) continue;

                BlockDisplay display = spawnLoc.getWorld().spawn(spawnLoc, BlockDisplay.class, entity -> {
                    entity.setBlock(material.createBlockData());
                    entity.setBrightness(new Display.Brightness(15, 15)); // Full brightness

                    // Set interpolation for smooth movement
                    entity.setInterpolationDuration(2); // 2 ticks interpolation
                    entity.setInterpolationDelay(0);

                    // Start invisible
                    entity.setTransformation(createTransformation(0, 0, 0, 0.5f));

                    // Prevent persistence/saving issues
                    entity.setPersistent(false);
                });

                pool.put(entityId, display);
                entityTypes.put(display.getUniqueId(), EntityType.BLOCK_DISPLAY);
            }

            plugin.getLogger().info("Initialized " + count + " block displays for zone '" + zoneName + "'");
        });
    }

    /**
     * Initialize a pool of text display entities for a zone.
     */
    public void initializeTextPool(String zoneName, int count) {
        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
        if (zone == null) return;

        Map<String, Entity> pool = entityPools.computeIfAbsent(zoneName.toLowerCase(), k -> new ConcurrentHashMap<>());

        Bukkit.getScheduler().runTask(plugin, () -> {
            Location spawnLoc = zone.getOrigin().clone();

            for (int i = 0; i < count; i++) {
                String entityId = "text_" + i;
                if (pool.containsKey(entityId)) continue;

                TextDisplay display = spawnLoc.getWorld().spawn(spawnLoc, TextDisplay.class, entity -> {
                    entity.setText("");
                    entity.setBillboard(Display.Billboard.CENTER);
                    entity.setBrightness(new Display.Brightness(15, 15));
                    entity.setInterpolationDuration(2);
                    entity.setInterpolationDelay(0);
                    entity.setPersistent(false);
                });

                pool.put(entityId, display);
                entityTypes.put(display.getUniqueId(), EntityType.TEXT_DISPLAY);
            }
        });
    }

    /**
     * Get an entity from the pool.
     */
    public Entity getEntity(String zoneName, String entityId) {
        Map<String, Entity> pool = entityPools.get(zoneName.toLowerCase());
        if (pool == null) return null;
        return pool.get(entityId);
    }

    /**
     * Update entity position with interpolation.
     */
    public void updateEntityPosition(String zoneName, String entityId, double x, double y, double z) {
        Entity entity = getEntity(zoneName, entityId);
        if (entity == null || !(entity instanceof Display display)) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            // Teleport to new location
            Location newLoc = entity.getLocation().clone();
            newLoc.setX(x);
            newLoc.setY(y);
            newLoc.setZ(z);
            entity.teleport(newLoc);
        });
    }

    /**
     * Update entity transformation (position offset, scale, rotation).
     */
    public void updateEntityTransformation(String zoneName, String entityId,
                                           float tx, float ty, float tz,
                                           float scale) {
        Entity entity = getEntity(zoneName, entityId);
        if (entity == null || !(entity instanceof Display display)) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            display.setTransformation(createTransformation(tx, ty, tz, scale));
        });
    }

    /**
     * Update entity transformation with rotation.
     */
    public void updateEntityTransformation(String zoneName, String entityId,
                                           float tx, float ty, float tz,
                                           float scale, float rotationY) {
        Entity entity = getEntity(zoneName, entityId);
        if (entity == null || !(entity instanceof Display display)) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            Transformation transform = new Transformation(
                new Vector3f(tx, ty, tz),  // translation
                new AxisAngle4f((float) Math.toRadians(rotationY), 0, 1, 0), // left rotation
                new Vector3f(scale, scale, scale), // scale
                new AxisAngle4f(0, 0, 0, 1) // right rotation
            );
            display.setTransformation(transform);
        });
    }

    /**
     * Set entity visibility by scaling to 0.
     */
    public void setEntityVisible(String zoneName, String entityId, boolean visible) {
        Entity entity = getEntity(zoneName, entityId);
        if (entity == null || !(entity instanceof Display display)) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            Transformation current = display.getTransformation();
            float scale = visible ? 0.5f : 0f;
            display.setTransformation(new Transformation(
                current.getTranslation(),
                current.getLeftRotation(),
                new Vector3f(scale, scale, scale),
                current.getRightRotation()
            ));
        });
    }

    /**
     * Update text display content.
     */
    public void updateTextContent(String zoneName, String entityId, String text) {
        Entity entity = getEntity(zoneName, entityId);
        if (entity == null || !(entity instanceof TextDisplay display)) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            display.setText(text);
        });
    }

    /**
     * Update block display material.
     */
    public void updateBlockMaterial(String zoneName, String entityId, Material material) {
        Entity entity = getEntity(zoneName, entityId);
        if (entity == null || !(entity instanceof BlockDisplay display)) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            display.setBlock(material.createBlockData());
        });
    }

    /**
     * Cleanup all entities in a zone.
     */
    public void cleanupZone(String zoneName) {
        Map<String, Entity> pool = entityPools.remove(zoneName.toLowerCase());
        if (pool == null) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Entity entity : pool.values()) {
                if (entity != null && entity.isValid()) {
                    entityTypes.remove(entity.getUniqueId());
                    entity.remove();
                }
            }
        });

        plugin.getLogger().info("Cleaned up entity pool for zone '" + zoneName + "'");
    }

    /**
     * Cleanup all entity pools.
     */
    public void cleanupAll() {
        for (String zoneName : new ArrayList<>(entityPools.keySet())) {
            cleanupZone(zoneName);
        }
    }

    /**
     * Get entity count for a zone.
     */
    public int getEntityCount(String zoneName) {
        Map<String, Entity> pool = entityPools.get(zoneName.toLowerCase());
        return pool == null ? 0 : pool.size();
    }

    /**
     * Get all entity IDs in a zone.
     */
    public Set<String> getEntityIds(String zoneName) {
        Map<String, Entity> pool = entityPools.get(zoneName.toLowerCase());
        return pool == null ? Collections.emptySet() : pool.keySet();
    }

    /**
     * Create a standard transformation.
     */
    private Transformation createTransformation(float tx, float ty, float tz, float scale) {
        return new Transformation(
            new Vector3f(tx, ty, tz),
            new AxisAngle4f(0, 0, 0, 1),
            new Vector3f(scale, scale, scale),
            new AxisAngle4f(0, 0, 0, 1)
        );
    }
}
