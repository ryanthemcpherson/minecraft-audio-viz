package com.audioviz.entities;

import com.audioviz.AudioVizPlugin;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.Bukkit;
import org.bukkit.Color;
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
import java.util.List;

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

    // Limits to prevent memory issues
    private static final int MAX_ZONES = 100;
    private final int maxEntitiesPerZone;

    public enum EntityType {
        BLOCK_DISPLAY,
        ITEM_DISPLAY,
        TEXT_DISPLAY
    }

    public EntityPoolManager(AudioVizPlugin plugin) {
        this.plugin = plugin;
        this.entityPools = new ConcurrentHashMap<>();
        this.entityTypes = new ConcurrentHashMap<>();
        this.maxEntitiesPerZone = plugin.getConfig().getInt("performance.max_entities_per_zone", 1000);
    }

    /**
     * Initialize a pool of block display entities for a zone.
     * Supports incremental resizing - adds or removes entities as needed instead of full cleanup.
     * Always updates block material on all existing entities.
     */
    public void initializeBlockPool(String zoneName, int count, Material material) {
        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
        if (zone == null) {
            plugin.getLogger().warning("Cannot initialize pool: zone '" + zoneName + "' not found");
            return;
        }

        // Enforce limits
        if (entityPools.size() >= MAX_ZONES && !entityPools.containsKey(zoneName.toLowerCase())) {
            plugin.getLogger().warning("Cannot initialize pool: max zones limit reached (" + MAX_ZONES + ")");
            return;
        }
        if (count > maxEntitiesPerZone) {
            plugin.getLogger().warning("Entity count capped from " + count + " to " + maxEntitiesPerZone + " (performance.max_entities_per_zone)");
            count = maxEntitiesPerZone;
        }

        final int finalCount = count;
        final Material finalMaterial = material;
        Map<String, Entity> pool = entityPools.computeIfAbsent(zoneName.toLowerCase(), k -> new ConcurrentHashMap<>());
        final int currentCount = pool.size();

        // Schedule on main thread for entity operations
        Bukkit.getScheduler().runTask(plugin, () -> {
            Location spawnLoc = zone.getOrigin().clone();

            // Update material on all existing entities
            for (Entity entity : pool.values()) {
                if (entity instanceof BlockDisplay display && entity.isValid()) {
                    display.setBlock(finalMaterial.createBlockData());
                }
            }

            if (finalCount > currentCount) {
                // Add more entities
                for (int i = currentCount; i < finalCount; i++) {
                    String entityId = "block_" + i;
                    if (pool.containsKey(entityId)) continue;

                    BlockDisplay display = spawnLoc.getWorld().spawn(spawnLoc, BlockDisplay.class, entity -> {
                        entity.setBlock(finalMaterial.createBlockData());
                        entity.setBrightness(new Display.Brightness(15, 15));
                        entity.setInterpolationDuration(1); // 1 tick keeps motion smooth but snappier
                        entity.setInterpolationDelay(0);
                        entity.setTeleportDuration(1); // Smooth position changes over 1 tick
                        entity.setTransformation(createTransformation(0, 0, 0, 0.5f));
                        entity.setPersistent(false);
                    });

                    pool.put(entityId, display);
                    entityTypes.put(display.getUniqueId(), EntityType.BLOCK_DISPLAY);
                }
                plugin.getLogger().info("Added " + (finalCount - currentCount) + " block displays for zone '" + zoneName + "' (now " + finalCount + ")");
            } else if (finalCount < currentCount) {
                // Remove excess entities
                for (int i = finalCount; i < currentCount; i++) {
                    String entityId = "block_" + i;
                    Entity entity = pool.remove(entityId);
                    if (entity != null && entity.isValid()) {
                        entityTypes.remove(entity.getUniqueId());
                        entity.remove();
                    }
                }
                plugin.getLogger().info("Removed " + (currentCount - finalCount) + " block displays from zone '" + zoneName + "' (now " + finalCount + ")");
            }

            plugin.getLogger().info("Block material set to " + finalMaterial.name() + " for zone '" + zoneName + "' (" + pool.size() + " entities)");
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
                    entity.setInterpolationDuration(1);
                    entity.setInterpolationDelay(0);
                    entity.setTeleportDuration(1); // Smooth position changes over 1 tick
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
     * PERFORMANCE: Batch update multiple entities in a single scheduler task.
     * This is much more efficient than individual updates when updating many entities.
     *
     * @param zoneName The zone containing the entities
     * @param updates List of entity updates to apply
     */
    public void batchUpdateEntities(String zoneName, List<EntityUpdate> updates) {
        if (updates == null || updates.isEmpty()) return;

        Map<String, Entity> pool = entityPools.get(zoneName.toLowerCase());
        if (pool == null) return;

        // Record stats
        plugin.getEntityUpdateStats().recordUpdates(updates.size());

        // Single scheduler call for ALL updates - major performance improvement
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (EntityUpdate update : updates) {
                Entity entity = pool.get(update.entityId());
                if (entity == null || !entity.isValid()) continue;

                if (!(entity instanceof Display display)) continue;

                // Apply location update
                if (update.hasLocation() && update.location() != null) {
                    entity.teleport(update.location());
                }

                // Apply per-entity interpolation duration if specified
                if (update.hasInterpolation()) {
                    display.setInterpolationDuration(update.interpolationDuration());
                }

                // Apply transformation update (only if changed to avoid resetting interpolation)
                if (update.hasTransform() && update.transformation() != null) {
                    Transformation currentTransform = display.getTransformation();
                    if (!update.transformation().equals(currentTransform)) {
                        display.setTransformation(update.transformation());
                        display.setInterpolationDelay(0); // Start interpolation immediately
                    }
                }

                // Apply brightness update
                if (update.hasBrightness()) {
                    int brightness = Math.max(0, Math.min(15, update.brightness()));
                    display.setBrightness(new Display.Brightness(brightness, brightness));
                }

                // Apply glow update
                if (update.hasGlow()) {
                    entity.setGlowing(update.glow());
                }
            }
        });
    }

    /**
     * Update entity position with interpolation.
     * Note: Creates an individual scheduler task. For bulk updates, prefer batchUpdateEntities().
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
            float rotRad = (float) Math.toRadians(rotationY);
            Vector3f translation = centeredScaleTranslation(tx, ty, tz, scale, rotRad);
            Transformation transform = new Transformation(
                translation,
                new AxisAngle4f(rotRad, 0, 1, 0), // left rotation
                new Vector3f(scale, scale, scale), // scale
                new AxisAngle4f(0, 0, 0, 1) // right rotation
            );
            display.setTransformation(transform);
        });
    }

    /**
     * Batch update visibility for multiple entities in a single scheduler task.
     *
     * @param zoneName The zone containing the entities
     * @param changes List of (entityId, visible) pairs
     */
    public void batchSetVisible(String zoneName, List<Map.Entry<String, Boolean>> changes) {
        if (changes == null || changes.isEmpty()) return;

        Map<String, Entity> pool = entityPools.get(zoneName.toLowerCase());
        if (pool == null) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Map.Entry<String, Boolean> change : changes) {
                Entity entity = pool.get(change.getKey());
                if (entity == null || !(entity instanceof Display display)) continue;

                Transformation current = display.getTransformation();
                float scale = change.getValue() ? 0.5f : 0f;
                Vector3f translation = centeredScaleTranslation(0f, 0f, 0f, scale);
                display.setTransformation(new Transformation(
                    translation,
                    current.getLeftRotation(),
                    new Vector3f(scale, scale, scale),
                    current.getRightRotation()
                ));
            }
        });
    }

    /**
     * Set entity visibility by scaling to 0.
     * Note: Creates an individual scheduler task. For bulk updates, prefer batchSetVisible().
     */
    public void setEntityVisible(String zoneName, String entityId, boolean visible) {
        Entity entity = getEntity(zoneName, entityId);
        if (entity == null || !(entity instanceof Display display)) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            Transformation current = display.getTransformation();
            float scale = visible ? 0.5f : 0f;
            Vector3f translation = centeredScaleTranslation(0f, 0f, 0f, scale);
            display.setTransformation(new Transformation(
                translation,
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
     * Initialize a pool of text display entities with a specific billboard mode.
     * Used by the banner decorator which needs FIXED billboard instead of CENTER.
     */
    public void initializeTextPool(String zoneName, int count, Display.Billboard billboardMode) {
        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
        if (zone == null) return;

        if (count > maxEntitiesPerZone) {
            count = maxEntitiesPerZone;
        }

        Map<String, Entity> pool = entityPools.computeIfAbsent(zoneName.toLowerCase(), k -> new ConcurrentHashMap<>());

        final int finalCount = count;
        final Display.Billboard mode = billboardMode;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Location spawnLoc = zone.getOrigin().clone();

            for (int i = 0; i < finalCount; i++) {
                String entityId = "text_" + i;
                if (pool.containsKey(entityId)) continue;

                TextDisplay display = spawnLoc.getWorld().spawn(spawnLoc, TextDisplay.class, entity -> {
                    entity.setText("");
                    entity.setBillboard(mode);
                    entity.setBrightness(new Display.Brightness(15, 15));
                    entity.setInterpolationDuration(1);
                    entity.setInterpolationDelay(0);
                    entity.setTeleportDuration(1);
                    entity.setPersistent(false);
                });

                pool.put(entityId, display);
                entityTypes.put(display.getUniqueId(), EntityType.TEXT_DISPLAY);
            }
        });
    }

    /**
     * Batch update TextDisplay background colors for pixel-art banner rendering.
     * Sets background color, text content, and billboard mode in a single scheduler task.
     */
    public void batchUpdateTextBackgrounds(String zoneName, Map<String, Color> colorMap) {
        if (colorMap == null || colorMap.isEmpty()) return;

        Map<String, Entity> pool = entityPools.get(zoneName.toLowerCase());
        if (pool == null) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Map.Entry<String, Color> entry : colorMap.entrySet()) {
                Entity entity = pool.get(entry.getKey());
                if (entity instanceof TextDisplay display) {
                    display.setBackgroundColor(entry.getValue());
                    display.setText(" "); // Single space creates a colored rectangle
                }
            }
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

        runEntityMutation(() -> {
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
     * Cleanup all entities in a zone synchronously.
     * Intended for plugin shutdown, where scheduling new tasks may be illegal.
     */
    public void cleanupZoneSync(String zoneName) {
        Map<String, Entity> pool = entityPools.remove(zoneName.toLowerCase());
        if (pool == null) return;

        for (Entity entity : pool.values()) {
            if (entity != null && entity.isValid()) {
                entityTypes.remove(entity.getUniqueId());
                entity.remove();
            }
        }

        plugin.getLogger().info("Synchronously cleaned up entity pool for zone '" + zoneName + "'");
    }

    /**
     * Cleanup all entity pools synchronously.
     * Intended for plugin shutdown.
     */
    public void cleanupAllSync() {
        for (String zoneName : new ArrayList<>(entityPools.keySet())) {
            cleanupZoneSync(zoneName);
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
        Vector3f translation = centeredScaleTranslation(tx, ty, tz, scale);
        return new Transformation(
            translation,
            new AxisAngle4f(0, 0, 0, 1),
            new Vector3f(scale, scale, scale),
            new AxisAngle4f(0, 0, 0, 1)
        );
    }

    /**
     * Offset translation so scaling happens around block center instead of a corner.
     */
    private Vector3f centeredScaleTranslation(float tx, float ty, float tz, float scale) {
        float pivotOffset = (1.0f - scale) * 0.5f;
        return new Vector3f(tx + pivotOffset, ty + pivotOffset, tz + pivotOffset);
    }

    /**
     * Rotation-aware pivot: compute translation that keeps the scaled+rotated
     * block centered at (0.5, 0.5, 0.5) within its unit cell.
     * LeftRotation rotates the scaled model around (0,0,0), shifting its center;
     * this compensates so the visual center stays put.
     */
    private Vector3f centeredScaleTranslation(float tx, float ty, float tz, float scale, float rotationRadians) {
        float halfScale = scale * 0.5f;
        float cosR = (float) Math.cos(rotationRadians);
        float sinR = (float) Math.sin(rotationRadians);
        float px = 0.5f - halfScale * (cosR + sinR);
        float py = 0.5f - halfScale;
        float pz = 0.5f - halfScale * (cosR - sinR);
        return new Vector3f(tx + px, ty + py, tz + pz);
    }

    // === Display Entity Feature Methods ===

    /**
     * Set entity glow effect (makes entity outline visible).
     */
    public void setEntityGlow(String zoneName, String entityId, boolean glow) {
        Entity entity = getEntity(zoneName, entityId);
        if (entity == null) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            entity.setGlowing(glow);
        });
    }

    /**
     * Set all entities in a zone to glow.
     */
    public void setZoneGlow(String zoneName, boolean glow) {
        Map<String, Entity> pool = entityPools.get(zoneName.toLowerCase());
        if (pool == null) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Entity entity : pool.values()) {
                if (entity != null && entity.isValid()) {
                    entity.setGlowing(glow);
                }
            }
        });
    }

    /**
     * Set brightness for a single entity (0-15).
     */
    public void setEntityBrightness(String zoneName, String entityId, int brightness) {
        Entity entity = getEntity(zoneName, entityId);
        if (entity == null || !(entity instanceof Display display)) return;

        // Clamp brightness to valid range
        int blockLight = Math.max(0, Math.min(15, brightness));
        int skyLight = Math.max(0, Math.min(15, brightness));

        Bukkit.getScheduler().runTask(plugin, () -> {
            display.setBrightness(new Display.Brightness(blockLight, skyLight));
        });
    }

    /**
     * Set brightness for all entities in a zone.
     */
    public void setZoneBrightness(String zoneName, int brightness) {
        Map<String, Entity> pool = entityPools.get(zoneName.toLowerCase());
        if (pool == null) return;

        int blockLight = Math.max(0, Math.min(15, brightness));
        int skyLight = Math.max(0, Math.min(15, brightness));

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Entity entity : pool.values()) {
                if (entity instanceof Display display) {
                    display.setBrightness(new Display.Brightness(blockLight, skyLight));
                }
            }
        });
    }

    /**
     * Set interpolation duration for all entities in a zone.
     */
    public void setZoneInterpolation(String zoneName, int ticks) {
        Map<String, Entity> pool = entityPools.get(zoneName.toLowerCase());
        if (pool == null) return;

        int duration = Math.max(0, Math.min(100, ticks));

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Entity entity : pool.values()) {
                if (entity instanceof Display display) {
                    display.setInterpolationDuration(duration);
                }
            }
        });
    }

    /**
     * Set interpolation for a single entity.
     */
    public void setEntityInterpolation(String zoneName, String entityId, int ticks) {
        Entity entity = getEntity(zoneName, entityId);
        if (entity == null || !(entity instanceof Display display)) return;

        int duration = Math.max(0, Math.min(100, ticks));

        Bukkit.getScheduler().runTask(plugin, () -> {
            display.setInterpolationDuration(duration);
        });
    }

    /**
     * Update entity with full display properties (brightness, glow, scale, rotation).
     * Used for beat-reactive effects.
     */
    public void updateEntityFull(String zoneName, String entityId,
                                  float tx, float ty, float tz,
                                  float scale, float rotationY,
                                  int brightness, boolean glow) {
        Entity entity = getEntity(zoneName, entityId);
        if (entity == null || !(entity instanceof Display display)) return;

        int blockLight = Math.max(0, Math.min(15, brightness));

        Bukkit.getScheduler().runTask(plugin, () -> {
            // Set transformation with rotation-aware pivot
            float rotRad = (float) Math.toRadians(rotationY);
            Vector3f translation = centeredScaleTranslation(tx, ty, tz, scale, rotRad);
            Transformation transform = new Transformation(
                translation,
                new AxisAngle4f(rotRad, 0, 1, 0),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f(0, 0, 0, 1)
            );
            display.setTransformation(transform);

            // Set brightness
            display.setBrightness(new Display.Brightness(blockLight, blockLight));

            // Set glow
            entity.setGlowing(glow);
        });
    }

    /**
     * Execute entity mutations on the main thread when possible.
     * During plugin shutdown, fall back to direct execution to avoid
     * IllegalPluginAccessException from scheduling APIs.
     */
    private void runEntityMutation(Runnable task) {
        if (!plugin.isEnabled() || Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }
}
