package com.audioviz.protocol.handlers;

import com.audioviz.AudioVizPlugin;
import com.audioviz.bitmap.BitmapRendererBackend;
import com.audioviz.bitmap.BitmapPatternManager;
import com.audioviz.entities.EntityPoolManager;
import com.audioviz.entities.EntityUpdate;
import com.audioviz.patterns.AudioState;
import com.audioviz.protocol.InputSanitizer;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageZoneConfig;
import com.audioviz.stages.StageZoneRole;
import com.audioviz.zones.VisualizationZone;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles core entity and zone management messages:
 * ping, get_zones, get_zone, init_pool, batch_update, update_entity,
 * set_visible, cleanup_zone, set_zone_config, set_entity_glow,
 * set_entity_brightness, query_zone_status.
 */
public class CoreEntityHandler extends BaseHandler {

    public CoreEntityHandler(AudioVizPlugin plugin) {
        super(plugin);
    }

    @Override
    public String[] getMessageTypes() {
        return new String[]{
            "ping", "get_zones", "get_zone", "init_pool", "batch_update",
            "update_entity", "set_visible", "cleanup_zone", "set_zone_config",
            "set_entity_glow", "set_entity_brightness", "query_zone_status"
        };
    }

    @Override
    public JsonObject handle(String type, JsonObject message) {
        return switch (type) {
            case "ping" -> handlePing();
            case "get_zones" -> handleGetZones();
            case "get_zone" -> handleGetZone(message);
            case "init_pool" -> handleInitPool(message);
            case "batch_update" -> handleBatchUpdate(message);
            case "update_entity" -> handleUpdateEntity(message);
            case "set_visible" -> handleSetVisible(message);
            case "cleanup_zone" -> handleCleanupZone(message);
            case "set_zone_config" -> handleSetZoneConfig(message);
            case "set_entity_glow" -> handleSetEntityGlow(message);
            case "set_entity_brightness" -> handleSetEntityBrightness(message);
            case "query_zone_status" -> handleQueryZoneStatus();
            default -> createError("Unknown message type: " + type);
        };
    }

    private JsonObject handlePing() {
        JsonObject response = new JsonObject();
        response.addProperty("type", "pong");
        response.addProperty("timestamp", System.currentTimeMillis());
        return response;
    }

    private JsonObject handleGetZones() {
        JsonObject response = new JsonObject();
        response.addProperty("type", "zones");

        JsonArray zonesArray = new JsonArray();
        for (VisualizationZone zone : plugin.getZoneManager().getAllZones()) {
            zonesArray.add(zoneToJson(zone));
        }
        response.add("zones", zonesArray);

        return response;
    }

    private JsonObject handleGetZone(JsonObject message) {
        if (!message.has("zone")) {
            return createError("Missing required field: zone");
        }
        String zoneName = message.get("zone").getAsString();
        if (!isValidZoneName(zoneName)) {
            return createError("Invalid zone name");
        }
        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);

        if (zone == null) {
            return createError("Zone not found: " + zoneName);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "zone");
        response.add("zone", zoneToJson(zone));
        response.addProperty("entity_count", plugin.getEntityPoolManager().getEntityCount(zoneName));

        return response;
    }

    private JsonObject handleInitPool(JsonObject message) {
        if (!message.has("zone")) {
            return createError("Missing required field: zone");
        }
        String zoneName = message.get("zone").getAsString();
        if (!isValidZoneName(zoneName)) {
            plugin.getLogger().warning("Invalid zone name in init_pool: " + zoneName);
            return createError("Invalid zone name");
        }
        int count = message.has("count") ? message.get("count").getAsInt() :
            plugin.getConfig().getInt("defaults.entity_count", 16);
        int maxEntities = plugin.getConfig().getInt("max-entities-per-zone", 1000);
        count = Math.max(0, Math.min(count, maxEntities));
        String materialName = message.has("material") ? message.get("material").getAsString() : "GLOWSTONE";

        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            material = Material.GLOWSTONE;
        }

        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        plugin.getEntityPoolManager().initializeBlockPool(zoneName, count, material);

        JsonObject response = new JsonObject();
        response.addProperty("type", "pool_initialized");
        response.addProperty("zone", zoneName);
        response.addProperty("count", count);
        response.addProperty("material", material.name());

        return response;
    }

    /**
     * Handle batch entity updates - the main visualization update method.
     */
    private JsonObject handleBatchUpdate(JsonObject message) {
        if (!message.has("zone")) {
            return createError("Missing required field: zone");
        }
        String zoneName = message.get("zone").getAsString();
        if (!isValidZoneName(zoneName)) {
            plugin.getLogger().warning("Invalid zone name in batch_update: " + zoneName);
            return createError("Invalid zone name");
        }
        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);

        if (zone == null) {
            return createError("Zone not found: " + zoneName);
        }

        EntityPoolManager pool = plugin.getEntityPoolManager();
        int updatedCount = 0;

        // Process entity updates — build batch list for single scheduler call
        if (message.has("entities")) {
            JsonArray entities = message.getAsJsonArray("entities");
            List<EntityUpdate> batchUpdates = new ArrayList<>(entities.size());

            for (JsonElement elem : entities) {
                JsonObject entity = elem.getAsJsonObject();
                if (!entity.has("id")) continue;
                String entityId = entity.get("id").getAsString();

                // Get position (local coordinates 0-1, clamped for safety)
                double localX = InputSanitizer.sanitizeCoordinate(
                    entity.has("x") ? entity.get("x").getAsDouble() : 0.5);
                double localY = InputSanitizer.sanitizeCoordinate(
                    entity.has("y") ? entity.get("y").getAsDouble() : 0.0);
                double localZ = InputSanitizer.sanitizeCoordinate(
                    entity.has("z") ? entity.get("z").getAsDouble() : 0.5);

                // Convert to world coordinates
                Location worldLoc = zone.localToWorld(localX, localY, localZ);

                // Build EntityUpdate with all properties in one object
                EntityUpdate.Builder builder = EntityUpdate.builder(entityId)
                    .location(worldLoc);

                // Check visibility — if hidden, force scale to 0 in the same transform
                boolean visible = !entity.has("visible") || entity.get("visible").getAsBoolean();

                // Add transformation if scale provided or entity is hidden (clamped to [0, 4])
                if (entity.has("scale") || !visible) {
                    float scale = !visible ? 0f
                        : InputSanitizer.sanitizeScale(entity.get("scale").getAsFloat());
                    float rotation = InputSanitizer.sanitizeRotation(
                        entity.has("rotation") ? entity.get("rotation").getAsFloat() : 0);
                    // Center block visual on entity position: T = -scale/2
                    float halfScale = scale * 0.5f;
                    float pivotX, pivotY, pivotZ;
                    if (rotation == 0f) {
                        pivotX = -halfScale;
                        pivotY = -halfScale;
                        pivotZ = -halfScale;
                    } else {
                        float rotRad = (float) Math.toRadians(rotation);
                        float cosR = (float) Math.cos(rotRad);
                        float sinR = (float) Math.sin(rotRad);
                        pivotX = -halfScale * cosR + halfScale * sinR;
                        pivotY = -halfScale;
                        pivotZ = -halfScale * sinR - halfScale * cosR;
                    }
                    builder.transformation(new Transformation(
                        new Vector3f(pivotX, pivotY, pivotZ),
                        new AxisAngle4f((float) Math.toRadians(rotation), 0, 1, 0),
                        new Vector3f(scale, scale, scale),
                        new AxisAngle4f(0, 0, 0, 1)
                    ));
                }

                // Add glow if provided
                if (entity.has("glow")) {
                    builder.glow(entity.get("glow").getAsBoolean());
                }

                // Add brightness if provided (clamped to [0, 15])
                if (entity.has("brightness")) {
                    builder.brightness(InputSanitizer.sanitizeBrightness(entity.get("brightness").getAsInt()));
                }

                // Add interpolation if provided (clamped to [0, 100])
                if (entity.has("interpolation")) {
                    builder.interpolationDuration(InputSanitizer.sanitizeInterpolation(entity.get("interpolation").getAsInt()));
                }

                // Add material if provided (per-entity block type override)
                if (entity.has("material")) {
                    String mat = entity.get("material").getAsString();
                    if (mat != null && !mat.isEmpty()) {
                        builder.material(mat);
                    }
                }

                batchUpdates.add(builder.build());
                updatedCount++;
            }

            // Single scheduler call for ALL entity updates
            if (!batchUpdates.isEmpty()) {
                pool.batchUpdateEntities(zoneName, batchUpdates);
            }
        }

        // Process particle effects (with aggregate cap to prevent lag spikes)
        if (message.has("particles")) {
            JsonArray particles = message.getAsJsonArray("particles");
            int totalParticles = 0;
            final int MAX_PARTICLES_PER_TICK = 2000;

            for (JsonElement elem : particles) {
                JsonObject particle = elem.getAsJsonObject();
                int particleCount = Math.min(particle.has("count") ? particle.get("count").getAsInt() : 10, 200);
                totalParticles += particleCount;
                if (totalParticles > MAX_PARTICLES_PER_TICK) {
                    break; // Stop spawning more particles this tick
                }
                spawnParticle(zone, particle);
            }
        }

        // Forward audio data to bitmap pattern manager (if present in the batch_update).
        if (message.has("bands") && plugin.getBitmapPatternManager() != null) {
            JsonArray bandsJson = message.getAsJsonArray("bands");
            int bandCount = Math.min(bandsJson.size(), 10);
            double[] bands = new double[bandCount];
            for (int i = 0; i < bands.length; i++) {
                bands[i] = InputSanitizer.sanitizeBandValue(bandsJson.get(i).getAsDouble());
            }
            double amplitude = InputSanitizer.sanitizeAmplitude(
                message.has("amplitude") ? message.get("amplitude").getAsDouble() : 0.0);
            boolean isBeat = message.has("is_beat") && message.get("is_beat").getAsBoolean();
            double beatIntensity = InputSanitizer.sanitizeDouble(
                message.has("beat_intensity") ? message.get("beat_intensity").getAsDouble() : 0.0,
                0.0, 1.0, 0.0);
            long frame = message.has("frame") ? message.get("frame").getAsLong() : 0;

            AudioState audioState = new AudioState(bands, amplitude, isBeat, beatIntensity, 0.0, 0.0, frame);
            plugin.getBitmapPatternManager().updateAudioState(audioState);
        }

        // Record network latency if timestamp present
        if (message.has("ts")) {
            double remoteTs = message.get("ts").getAsDouble();
            var latencyTracker = plugin.getLatencyTracker();
            if (latencyTracker != null) {
                latencyTracker.recordNetworkLatency(remoteTs, System.currentTimeMillis());
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "batch_updated");
        response.addProperty("zone", zoneName);
        response.addProperty("updated", updatedCount);

        return response;
    }

    private JsonObject handleUpdateEntity(JsonObject message) {
        if (!message.has("zone") || !message.has("id")) {
            return createError("Missing required field: zone or id");
        }
        String zoneName = message.get("zone").getAsString();
        if (!isValidZoneName(zoneName)) {
            return createError("Invalid zone name");
        }
        String entityId = message.get("id").getAsString();

        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        EntityPoolManager pool = plugin.getEntityPoolManager();
        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);

        if (message.has("x") && message.has("y") && message.has("z")) {
            double localX = InputSanitizer.sanitizeCoordinate(message.get("x").getAsDouble());
            double localY = InputSanitizer.sanitizeCoordinate(message.get("y").getAsDouble());
            double localZ = InputSanitizer.sanitizeCoordinate(message.get("z").getAsDouble());

            Location worldLoc = zone.localToWorld(localX, localY, localZ);
            pool.updateEntityPosition(zoneName, entityId, worldLoc.getX(), worldLoc.getY(), worldLoc.getZ());
        }

        if (message.has("scale")) {
            float scale = InputSanitizer.sanitizeScale(message.get("scale").getAsFloat());
            pool.updateEntityTransformation(zoneName, entityId, 0, 0, 0, scale);
        }

        if (message.has("visible")) {
            pool.setEntityVisible(zoneName, entityId, message.get("visible").getAsBoolean());
        }

        if (message.has("text")) {
            pool.updateTextContent(zoneName, entityId, message.get("text").getAsString());
        }

        if (message.has("material")) {
            Material mat = Material.matchMaterial(message.get("material").getAsString());
            if (mat != null) {
                pool.updateBlockMaterial(zoneName, entityId, mat);
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "entity_updated");
        response.addProperty("zone", zoneName);
        response.addProperty("id", entityId);

        return response;
    }

    private JsonObject handleSetVisible(JsonObject message) {
        if (!message.has("zone") || !message.has("visible")) {
            return createError("Missing required field: zone or visible");
        }
        String zoneName = message.get("zone").getAsString();
        if (!isValidZoneName(zoneName)) {
            return createError("Invalid zone name");
        }
        boolean visible = message.get("visible").getAsBoolean();

        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        EntityPoolManager pool = plugin.getEntityPoolManager();

        // If entity IDs provided, update those; otherwise update all
        if (message.has("entities")) {
            JsonArray entities = message.getAsJsonArray("entities");
            for (JsonElement elem : entities) {
                pool.setEntityVisible(zoneName, elem.getAsString(), visible);
            }
        } else {
            for (String entityId : pool.getEntityIds(zoneName)) {
                pool.setEntityVisible(zoneName, entityId, visible);
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "visibility_updated");
        response.addProperty("zone", zoneName);
        response.addProperty("visible", visible);

        return response;
    }

    private JsonObject handleCleanupZone(JsonObject message) {
        if (!message.has("zone")) {
            return createError("Missing required field: zone");
        }
        String zoneName = message.get("zone").getAsString();
        if (!isValidZoneName(zoneName)) {
            plugin.getLogger().warning("Invalid zone name in cleanup_zone: " + zoneName);
            return createError("Invalid zone name");
        }
        plugin.getEntityPoolManager().cleanupZone(zoneName);
        plugin.getParticleVisualizationManager().removeZoneConfig(zoneName);
        plugin.getRendererRegistry().removeZone(zoneName);

        JsonObject response = new JsonObject();
        response.addProperty("type", "zone_cleaned");
        response.addProperty("zone", zoneName);

        return response;
    }

    private JsonObject handleSetZoneConfig(JsonObject message) {
        if (!message.has("zone") || !message.has("config")) {
            return createError("Missing required field: zone or config");
        }
        String zoneName = message.get("zone").getAsString();
        if (!isValidZoneName(zoneName)) {
            plugin.getLogger().warning("Invalid zone name in set_zone_config: " + zoneName);
            return createError("Invalid zone name");
        }
        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);

        if (zone == null) {
            return createError("Zone not found: " + zoneName);
        }

        JsonObject config = message.getAsJsonObject("config");

        // Update zone size if provided
        if (config.has("size")) {
            JsonObject size = config.getAsJsonObject("size");
            double x = size.has("x") ? size.get("x").getAsDouble() : zone.getSize().getX();
            double y = size.has("y") ? size.get("y").getAsDouble() : zone.getSize().getY();
            double z = size.has("z") ? size.get("z").getAsDouble() : zone.getSize().getZ();
            zone.setSize(new org.bukkit.util.Vector(x, y, z));
        }

        // Update rotation if provided
        if (config.has("rotation")) {
            zone.setRotation(config.get("rotation").getAsFloat());
        }

        // Save zone changes
        plugin.getZoneManager().saveZones();

        // Update entity pool only if count or block type changed
        if (config.has("entity_count") || config.has("block_type")) {
            int entityCount = config.has("entity_count") ? config.get("entity_count").getAsInt()
                : plugin.getEntityPoolManager().getEntityCount(zoneName);
            String blockType = config.has("block_type") ? config.get("block_type").getAsString() : "SEA_LANTERN";

            Material material = Material.matchMaterial(blockType);
            if (material == null || !material.isBlock()) material = Material.SEA_LANTERN;

            plugin.getEntityPoolManager().initializeBlockPool(zoneName, entityCount, material);

            // Persist to stage config so changes survive MC restarts
            Stage stage = plugin.getStageManager().findStageForZone(zoneName);
            if (stage != null) {
                StageZoneRole role = stage.getRoleForZone(zoneName);
                if (role != null) {
                    StageZoneConfig stageConfig = stage.getOrCreateConfig(role);
                    if (config.has("entity_count")) {
                        stageConfig.setEntityCount(entityCount);
                    }
                    if (config.has("block_type")) {
                        stageConfig.setBlockType(blockType);
                    }
                    plugin.getStageManager().saveStages();
                }
            }
        }

        // Update display properties
        if (config.has("brightness")) {
            int brightness = config.get("brightness").getAsInt();
            plugin.getEntityPoolManager().setZoneBrightness(zoneName, brightness);
        }

        if (config.has("interpolation")) {
            int interpolation = config.get("interpolation").getAsInt();
            plugin.getEntityPoolManager().setZoneInterpolation(zoneName, interpolation);
        }

        // Update glow_on_beat setting
        if (config.has("glow_on_beat")) {
            zone.setGlowOnBeat(config.get("glow_on_beat").getAsBoolean());
        }

        // Update dynamic_brightness setting
        if (config.has("dynamic_brightness")) {
            zone.setDynamicBrightness(config.get("dynamic_brightness").getAsBoolean());
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "zone_config_updated");
        response.addProperty("zone", zoneName);
        response.add("config", config);

        return response;
    }

    private JsonObject handleSetEntityGlow(JsonObject message) {
        if (!message.has("zone") || !message.has("glow")) {
            return createError("Missing required field: zone or glow");
        }
        String zoneName = message.get("zone").getAsString();
        if (!isValidZoneName(zoneName)) {
            return createError("Invalid zone name");
        }
        boolean glow = message.get("glow").getAsBoolean();

        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        EntityPoolManager pool = plugin.getEntityPoolManager();

        if (message.has("entities")) {
            JsonArray entities = message.getAsJsonArray("entities");
            for (JsonElement elem : entities) {
                pool.setEntityGlow(zoneName, elem.getAsString(), glow);
            }
        } else {
            for (String entityId : pool.getEntityIds(zoneName)) {
                pool.setEntityGlow(zoneName, entityId, glow);
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "glow_updated");
        response.addProperty("zone", zoneName);
        response.addProperty("glow", glow);

        return response;
    }

    private JsonObject handleSetEntityBrightness(JsonObject message) {
        if (!message.has("zone") || !message.has("brightness")) {
            return createError("Missing required field: zone or brightness");
        }
        String zoneName = message.get("zone").getAsString();
        if (!isValidZoneName(zoneName)) {
            return createError("Invalid zone name");
        }
        int brightness = message.get("brightness").getAsInt();

        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        EntityPoolManager pool = plugin.getEntityPoolManager();

        if (message.has("entities")) {
            JsonArray entities = message.getAsJsonArray("entities");
            for (JsonElement elem : entities) {
                pool.setEntityBrightness(zoneName, elem.getAsString(), brightness);
            }
        } else {
            pool.setZoneBrightness(zoneName, brightness);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "brightness_updated");
        response.addProperty("zone", zoneName);
        response.addProperty("brightness", brightness);

        return response;
    }

    private JsonObject handleQueryZoneStatus() {
        JsonObject response = new JsonObject();
        response.addProperty("type", "zone_status_report");

        JsonObject zonesObj = new JsonObject();

        EntityPoolManager poolManager = plugin.getEntityPoolManager();
        BitmapRendererBackend bitmapRenderer = plugin.getBitmapRenderer();
        BitmapPatternManager bitmapPatternMgr = plugin.getBitmapPatternManager();

        for (VisualizationZone zone : plugin.getZoneManager().getAllZones()) {
            String name = zone.getName();
            JsonObject zoneInfo = new JsonObject();

            zoneInfo.addProperty("entity_count", poolManager.getEntityCount(name));
            boolean bitmapActive = bitmapRenderer.isBitmapZone(name);
            zoneInfo.addProperty("bitmap_active", bitmapActive);

            if (bitmapActive) {
                var gridConfig = bitmapRenderer.getGridConfig(name);
                zoneInfo.addProperty("bitmap_width", gridConfig.width());
                zoneInfo.addProperty("bitmap_height", gridConfig.height());
                String patternId = bitmapPatternMgr.getActivePatternId(name);
                if (patternId != null) {
                    zoneInfo.addProperty("bitmap_pattern", patternId);
                }
            } else {
                zoneInfo.addProperty("bitmap_width", 0);
                zoneInfo.addProperty("bitmap_height", 0);
            }

            zonesObj.add(name, zoneInfo);
        }

        response.add("zones", zonesObj);
        return response;
    }

    private void spawnParticle(VisualizationZone zone, JsonObject particle) {
        String particleName = particle.get("particle").getAsString();
        double localX = particle.has("x") ? particle.get("x").getAsDouble() : 0.5;
        double localY = particle.has("y") ? particle.get("y").getAsDouble() : 0.5;
        double localZ = particle.has("z") ? particle.get("z").getAsDouble() : 0.5;
        int count = Math.min(particle.has("count") ? particle.get("count").getAsInt() : 10, 200);

        try {
            org.bukkit.Particle bukkitParticle = org.bukkit.Particle.valueOf(particleName.toUpperCase());
            Location loc = zone.localToWorld(localX, localY, localZ);

            // Spawn on main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                zone.getWorld().spawnParticle(bukkitParticle, loc, count, 0.5, 0.5, 0.5, 0.1);
            });

        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown particle type: " + particleName);
        }
    }
}
