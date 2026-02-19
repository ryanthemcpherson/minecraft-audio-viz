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
    private static final int MAX_ENTITIES_PER_ZONE = 1000;

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
     * Supports incremental resizing - adds or removes entities as needed instead of full cleanup.
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
        if (count > MAX_ENTITIES_PER_ZONE) {
            plugin.getLogger().warning("Entity count capped from " + count + " to " + MAX_ENTITIES_PER_ZONE);
            count = MAX_ENTITIES_PER_ZONE;
        }

        final int finalCount = count;
        Map<String, Entity> pool = entityPools.computeIfAbsent(zoneName.toLowerCase(), k -> new ConcurrentHashMap<>());
        final int currentCount = pool.size();

        // If counts match, nothing to do
        if (currentCount == finalCount) {
            return;
        }

        // Incremental resize on the main thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            Location spawnLoc = zone.getOrigin().clone();

            if (finalCount > currentCount) {
                // Add more entities
                for (int i = currentCount; i < finalCount; i++) {
                    String entityId = "block_" + i;
                    if (pool.containsKey(entityId)) continue;

                    BlockDisplay display = spawnLoc.getWorld().spawn(spawnLoc, BlockDisplay.class, entity -> {
                        entity.setBlock(material.createBlockData());
                        entity.setBrightness(new Display.Brightness(15, 15));
                        entity.setInterpolationDuration(2); // 2 ticks - responsive interpolation
                        entity.setInterpolationDelay(0);
                        entity.setTeleportDuration(1); // Smooth position changes over 1 tick
                        entity.setTransformation(createTransformation(0, 0, 0, 0.5f));
                        entity.setPersistent(false);
                    });

                    pool.put(entityId, display);
                    entityTypes.put(display.getUniqueId(), EntityType.BLOCK_DISPLAY);
                }
                plugin.getLogger().info("Added " + (finalCount - currentCount) + " block displays for zone '" + zoneName + "' (now " + finalCount + ")");
            } else {
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
     * Initialize a pool of text display entities with a specific billboard mode.
     * Used by the banner decorator which needs FIXED billboard instead of CENTER.
     */
    public void initializeTextPool(String zoneName, int count, Display.Billboard billboardMode) {
        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
        if (zone == null) return;

        if (count > MAX_ENTITIES_PER_ZONE) {
            count = MAX_ENTITIES_PER_ZONE;
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
                    entity.setInterpolationDuration(2);
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
            // Set transformation
            Transformation transform = new Transformation(
                new Vector3f(tx, ty, tz),
                new AxisAngle4f((float) Math.toRadians(rotationY), 0, 1, 0),
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
}
