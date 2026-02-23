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
import net.kyori.adventure.text.Component;
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

    // Cache last applied material per entity to avoid redundant block data updates.
    // Nested map: zoneName (lowercase) → entityId → materialName.
    // Enables O(1) zone-level invalidation instead of prefix-scanning all keys.
    private final Map<String, Map<String, String>> entityMaterialCache;

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
        this.entityMaterialCache = new ConcurrentHashMap<>(); // zone → entityId → material
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

            // Reset all existing entities to clean state for the new pattern.
            // This clears per-entity material, glow, and brightness overrides
            // that the previous pattern may have applied.
            for (Entity entity : pool.values()) {
                if (entity.isValid()) {
                    if (entity instanceof BlockDisplay display) {
                        display.setBlock(finalMaterial.createBlockData());
                    }
                    if (entity instanceof Display display) {
                        display.setBrightness(BRIGHTNESS_CACHE[15]);
                    }
                    entity.setGlowing(false);
                }
            }

            // Invalidate material cache — init_pool reset all entities to the
            // pool material, so cached per-entity overrides are now stale.
            entityMaterialCache.remove(zoneName.toLowerCase());

            if (finalCount > currentCount) {
                // Add more entities — spawn at sibling positions so they
                // emerge from existing blocks rather than flying from origin.
                for (int i = currentCount; i < finalCount; i++) {
                    String entityId = "block_" + i;
                    if (pool.containsKey(entityId)) continue;

                    // Spawn at sibling entity's position (block_N % oldCount)
                    Location entitySpawnLoc = spawnLoc;
                    if (currentCount > 0) {
                        String siblingId = "block_" + (i % currentCount);
                        Entity sibling = pool.get(siblingId);
                        if (sibling != null && sibling.isValid()) {
                            entitySpawnLoc = sibling.getLocation().clone();
                        }
                    }

                    BlockDisplay display = entitySpawnLoc.getWorld().spawn(entitySpawnLoc, BlockDisplay.class, entity -> {
                        entity.setBlock(finalMaterial.createBlockData());
                        entity.setBrightness(new Display.Brightness(15, 15));
                        entity.setInterpolationDuration(2); // 2 ticks — completes 50% per tick, matching ~exponential decay
                        entity.setInterpolationDelay(0);
                        entity.setTeleportDuration(1); // Snap position quickly; server already smooths
                        // Spawn invisible (scale 0) — entities become visible when
                        // the first batch_update positions them properly. Prevents
                        // a stack of blocks stuck at the zone origin corner.
                        entity.setTransformation(createTransformation(0, 0, 0, 0f));
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

        if (count > maxEntitiesPerZone) {
            count = maxEntitiesPerZone;
        }

        final int finalCount = count;
        Map<String, Entity> pool = entityPools.computeIfAbsent(zoneName.toLowerCase(), k -> new ConcurrentHashMap<>());

        Bukkit.getScheduler().runTask(plugin, () -> {
            Location spawnLoc = zone.getOrigin().clone();

            for (int i = 0; i < finalCount; i++) {
                String entityId = "text_" + i;
                if (pool.containsKey(entityId)) continue;

                TextDisplay display = spawnLoc.getWorld().spawn(spawnLoc, TextDisplay.class, entity -> {
                    entity.setText("");
                    entity.setBillboard(Display.Billboard.CENTER);
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
     * Get an entity from the pool.
     */
    public Entity getEntity(String zoneName, String entityId) {
        Map<String, Entity> pool = entityPools.get(zoneName.toLowerCase());
        if (pool == null) return null;
        return pool.get(entityId);
    }

    // Cached Brightness instances (0-15). Avoids allocating new Brightness each tick per entity.
    private static final Display.Brightness[] BRIGHTNESS_CACHE = new Display.Brightness[16];
    static {
        for (int i = 0; i < 16; i++) {
            BRIGHTNESS_CACHE[i] = new Display.Brightness(i, i);
        }
    }

    // PERFORMANCE: Cache Material.matchMaterial() results to avoid repeated enum scans.
    // Material names are case-insensitive; matchMaterial does uppercase conversion + linear scan.
    // This turns O(n) enum lookups into O(1) hash lookups after first resolution.
    private static final ConcurrentHashMap<String, Material> MATERIAL_CACHE = new ConcurrentHashMap<>();

    /**
     * PERFORMANCE: Batch update multiple entities in a single scheduler task.
     * This is much more efficient than individual updates when updating many entities.
     *
     * @param zoneName The zone containing the entities
     * @param updates List of entity updates to apply
     */
    public void batchUpdateEntities(String zoneName, List<EntityUpdate> updates) {
        if (updates == null || updates.isEmpty()) return;

        String zoneKey = zoneName.toLowerCase();
        Map<String, Entity> pool = entityPools.get(zoneKey);
        if (pool == null) return;

        // Record stats
        plugin.getEntityUpdateStats().recordUpdates(updates.size());

        // Apply updates directly if on main thread (hot path from MessageQueue.processTick),
        // otherwise schedule via runTask for thread safety.
        Runnable applyUpdates = () -> {
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

                // Apply transformation update
                if (update.hasTransform() && update.transformation() != null) {
                    display.setTransformation(update.transformation());
                    display.setInterpolationDelay(0); // Start interpolation immediately
                }

                // Apply brightness update (use cached Brightness to avoid allocation)
                if (update.hasBrightness()) {
                    int brightness = Math.max(0, Math.min(15, update.brightness()));
                    display.setBrightness(BRIGHTNESS_CACHE[brightness]);
                }

                // Apply glow update
                if (update.hasGlow()) {
                    entity.setGlowing(update.glow());
                }

                // Apply material update (only when changed, to avoid redundant block data updates)
                if (update.hasMaterial() && update.material() != null && entity instanceof BlockDisplay blockDisplay) {
                    Map<String, String> zoneMatCache = entityMaterialCache
                        .computeIfAbsent(zoneKey, k -> new ConcurrentHashMap<>());
                    String lastMaterial = zoneMatCache.get(update.entityId());
                    if (!update.material().equals(lastMaterial)) {
                        Material mat = MATERIAL_CACHE.get(update.material());
                        if (mat == null) {
                            mat = Material.matchMaterial(update.material());
                            if (mat != null) {
                                MATERIAL_CACHE.put(update.material(), mat);
                            }
                        }
                        if (mat != null && mat.isBlock()) {
                            blockDisplay.setBlock(mat.createBlockData());
                        }
                        // Cache even invalid names to avoid repeated lookups
                        zoneMatCache.put(update.entityId(), update.material());
                    }
                }
            }
        };

        if (Bukkit.isPrimaryThread()) {
            applyUpdates.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, applyUpdates);
        }
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
     *
     * <p>Optimized for the bitmap render hot path: runs directly when already on the
     * main thread (avoids scheduler overhead at 20 TPS).
     */
    public boolean batchUpdateTextBackgrounds(String zoneName, Map<String, Color> colorMap) {
        if (colorMap == null || colorMap.isEmpty()) return false;

        Map<String, Entity> pool = entityPools.get(zoneName.toLowerCase());
        if (pool == null) return false;

        Runnable apply = () -> {
            for (Map.Entry<String, Color> entry : colorMap.entrySet()) {
                Entity entity = pool.get(entry.getKey());
                if (entity instanceof TextDisplay display) {
                    display.setBackgroundColor(entry.getValue());
                    // Only set text if not already a space (avoid redundant packet)
                    if (!" ".equals(display.getText())) {
                        display.setText(" ");
                    }
                }
            }
        };

        if (Bukkit.isPrimaryThread()) {
            apply.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, apply);
        }
        return true;
    }

    /**
     * Batch update TextDisplay backgrounds from raw ARGB pixel data.
     * Avoids creating intermediate Color objects for each pixel — significantly
     * reduces GC pressure on the bitmap hot path (called every tick at 20 TPS).
     *
     * @param zoneName  the zone to update
     * @param entityIds ordered entity ID array (index corresponds to argbPixels index)
     * @param argbPixels raw ARGB pixel values
     * @param count     number of entries to process
     */
    public boolean batchUpdateTextBackgroundsRaw(String zoneName, String[] entityIds, int[] argbPixels, int count) {
        if (entityIds == null || argbPixels == null || count <= 0) return false;

        Map<String, Entity> pool = entityPools.get(zoneName.toLowerCase());
        if (pool == null) return false;

        Runnable apply = () -> {
            for (int i = 0; i < count; i++) {
                Entity entity = pool.get(entityIds[i]);
                if (entity instanceof TextDisplay display) {
                    int argb = argbPixels[i];
                    display.setBackgroundColor(Color.fromARGB(
                        (argb >> 24) & 0xFF, (argb >> 16) & 0xFF,
                        (argb >> 8) & 0xFF, argb & 0xFF));
                    if (!" ".equals(display.getText())) {
                        display.setText(" ");
                    }
                }
            }
        };

        if (Bukkit.isPrimaryThread()) {
            apply.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, apply);
        }
        return true;
    }

    /**
     * Batch update for the adaptive bitmap renderer (no teleport, no brightness).
     */
    public void batchUpdateAdaptive(
            String zoneName,
            String[] geoIds, Transformation[] geoTransforms, int geoCount,
            String[] bgIds, int[] bgArgb, int bgCount,
            String[] txtIds, Component[] txtComponents, int txtCount,
            String[] hideIds, int hideCount) {
        batchUpdateAdaptive(zoneName, geoIds, geoTransforms, null, geoCount,
                bgIds, bgArgb, null, bgCount, txtIds, txtComponents, txtCount, hideIds, hideCount);
    }

    /**
     * Batch update for the adaptive bitmap renderer with teleport but no brightness.
     */
    public void batchUpdateAdaptive(
            String zoneName,
            String[] geoIds, Transformation[] geoTransforms, Location[] geoLocations, int geoCount,
            String[] bgIds, int[] bgArgb, int bgCount,
            String[] txtIds, Component[] txtComponents, int txtCount,
            String[] hideIds, int hideCount) {
        batchUpdateAdaptive(zoneName, geoIds, geoTransforms, geoLocations, geoCount,
                bgIds, bgArgb, null, bgCount, txtIds, txtComponents, txtCount, hideIds, hideCount);
    }

    /**
     * Batch update for the adaptive bitmap renderer with entity teleportation and brightness.
     * Applies geometry (transformation + teleport), background color + brightness, and text
     * updates in a single scheduler task to minimize main-thread scheduling overhead.
     *
     * @param geoLocations optional Location array for teleporting geometry-updated entities
     *                     (parallel to geoIds); null entries are skipped
     * @param bgBrightness optional int[] of brightness values (0-15) parallel to bgIds; null = no change
     */
    public void batchUpdateAdaptive(
            String zoneName,
            String[] geoIds, Transformation[] geoTransforms, Location[] geoLocations, int geoCount,
            String[] bgIds, int[] bgArgb, int[] bgBrightness, int bgCount,
            String[] txtIds, Component[] txtComponents, int txtCount,
            String[] hideIds, int hideCount) {

        Map<String, Entity> pool = entityPools.get(zoneName.toLowerCase());
        if (pool == null) return;

        Runnable apply = () -> {
            // Geometry updates (transformation + optional teleport)
            for (int i = 0; i < geoCount; i++) {
                Entity entity = pool.get(geoIds[i]);
                if (entity instanceof TextDisplay display) {
                    if (geoLocations != null && geoLocations[i] != null) {
                        display.teleport(geoLocations[i]);
                    }
                    display.setTransformation(geoTransforms[i]);
                    display.setInterpolationDelay(0);
                }
            }

            // Background color + optional brightness updates
            for (int i = 0; i < bgCount; i++) {
                Entity entity = pool.get(bgIds[i]);
                if (entity instanceof TextDisplay display) {
                    int argb = bgArgb[i];
                    display.setBackgroundColor(Color.fromARGB(
                        (argb >> 24) & 0xFF, (argb >> 16) & 0xFF,
                        (argb >> 8) & 0xFF, argb & 0xFF));
                    if (bgBrightness != null && i < bgBrightness.length) {
                        int b = Math.max(0, Math.min(15, bgBrightness[i]));
                        display.setBrightness(BRIGHTNESS_CACHE[b]);
                    }
                }
            }

            // Text updates (half-block color or space for uniform)
            for (int i = 0; i < txtCount; i++) {
                Entity entity = pool.get(txtIds[i]);
                if (entity instanceof TextDisplay display) {
                    display.text(txtComponents[i]);
                }
            }

            // Hide unused entities
            for (int i = 0; i < hideCount; i++) {
                Entity entity = pool.get(hideIds[i]);
                if (entity instanceof TextDisplay display) {
                    display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    display.text(Component.empty());
                }
            }
        };

        if (Bukkit.isPrimaryThread()) {
            apply.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, apply);
        }
    }

    /**
     * Register an externally-spawned entity pool with the pool manager.
     * Used by {@link com.audioviz.bitmap.BitmapRendererBackend} which spawns its own
     * TextDisplay grid but needs lifecycle tracking (cleanup, entity count queries).
     *
     * @param zoneName the zone name to associate with
     * @param pool     map of entityId → Entity, already spawned in-world
     */
    public void registerExternalPool(String zoneName, Map<String, Entity> pool) {
        if (pool == null || pool.isEmpty()) return;

        Map<String, Entity> existingPool = entityPools.computeIfAbsent(
            zoneName.toLowerCase(), k -> new ConcurrentHashMap<>());
        existingPool.putAll(pool);

        // Track entity types
        for (Entity entity : pool.values()) {
            if (entity instanceof TextDisplay) {
                entityTypes.put(entity.getUniqueId(), EntityType.TEXT_DISPLAY);
            } else if (entity instanceof BlockDisplay) {
                entityTypes.put(entity.getUniqueId(), EntityType.BLOCK_DISPLAY);
            } else if (entity instanceof ItemDisplay) {
                entityTypes.put(entity.getUniqueId(), EntityType.ITEM_DISPLAY);
            }
        }

        plugin.getLogger().info("Registered external pool for zone '" + zoneName +
            "' (" + pool.size() + " entities)");
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

        // Clear material cache entries for this zone (O(1) with nested map)
        entityMaterialCache.remove(zoneName.toLowerCase());

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

        Runnable apply = () -> {
            for (Entity entity : pool.values()) {
                if (entity != null && entity.isValid()) {
                    entity.setGlowing(glow);
                }
            }
        };
        if (Bukkit.isPrimaryThread()) {
            apply.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, apply);
        }
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

        Runnable apply = () -> {
            for (Entity entity : pool.values()) {
                if (entity instanceof Display display) {
                    display.setBrightness(new Display.Brightness(blockLight, skyLight));
                }
            }
        };
        if (Bukkit.isPrimaryThread()) {
            apply.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, apply);
        }
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
