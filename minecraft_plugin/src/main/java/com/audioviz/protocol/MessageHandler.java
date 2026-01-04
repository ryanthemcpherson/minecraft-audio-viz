package com.audioviz.protocol;

import com.audioviz.AudioVizPlugin;
import com.audioviz.entities.EntityPoolManager;
import com.audioviz.zones.VisualizationZone;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.Material;

/**
 * Handles WebSocket protocol messages and dispatches to appropriate handlers.
 */
public class MessageHandler {

    private final AudioVizPlugin plugin;

    public MessageHandler(AudioVizPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Handle an incoming message and return a response.
     */
    public JsonObject handleMessage(String type, JsonObject message) {
        return switch (type) {
            case "ping" -> handlePing();
            case "get_zones" -> handleGetZones();
            case "get_zone" -> handleGetZone(message);
            case "init_pool" -> handleInitPool(message);
            case "batch_update" -> handleBatchUpdate(message);
            case "update_entity" -> handleUpdateEntity(message);
            case "set_visible" -> handleSetVisible(message);
            case "cleanup_zone" -> handleCleanupZone(message);
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
        String zoneName = message.get("zone").getAsString();
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
        String zoneName = message.get("zone").getAsString();
        int count = message.has("count") ? message.get("count").getAsInt() : 16;
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
     * Expected format:
     * {
     *   "type": "batch_update",
     *   "zone": "zone_name",
     *   "entities": [
     *     {"id": "block_0", "x": 0.5, "y": 0.3, "z": 0.5, "scale": 0.5, "visible": true},
     *     ...
     *   ],
     *   "particles": [
     *     {"particle": "FLAME", "x": 0, "y": 10, "z": 0, "count": 50},
     *     ...
     *   ]
     * }
     */
    private JsonObject handleBatchUpdate(JsonObject message) {
        String zoneName = message.get("zone").getAsString();
        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);

        if (zone == null) {
            return createError("Zone not found: " + zoneName);
        }

        EntityPoolManager pool = plugin.getEntityPoolManager();
        int updatedCount = 0;

        // Process entity updates
        if (message.has("entities")) {
            JsonArray entities = message.getAsJsonArray("entities");

            for (JsonElement elem : entities) {
                JsonObject entity = elem.getAsJsonObject();
                String entityId = entity.get("id").getAsString();

                // Get position (local coordinates 0-1)
                double localX = entity.has("x") ? entity.get("x").getAsDouble() : 0.5;
                double localY = entity.has("y") ? entity.get("y").getAsDouble() : 0;
                double localZ = entity.has("z") ? entity.get("z").getAsDouble() : 0.5;

                // Convert to world coordinates
                Location worldLoc = zone.localToWorld(localX, localY, localZ);

                // Update position
                pool.updateEntityPosition(zoneName, entityId,
                    worldLoc.getX(), worldLoc.getY(), worldLoc.getZ());

                // Update scale if provided
                if (entity.has("scale")) {
                    float scale = entity.get("scale").getAsFloat();
                    float rotation = entity.has("rotation") ? entity.get("rotation").getAsFloat() : 0;
                    pool.updateEntityTransformation(zoneName, entityId, 0, 0, 0, scale, rotation);
                }

                // Update visibility if provided
                if (entity.has("visible")) {
                    boolean visible = entity.get("visible").getAsBoolean();
                    pool.setEntityVisible(zoneName, entityId, visible);
                }

                updatedCount++;
            }
        }

        // Process particle effects
        if (message.has("particles")) {
            JsonArray particles = message.getAsJsonArray("particles");

            for (JsonElement elem : particles) {
                JsonObject particle = elem.getAsJsonObject();
                spawnParticle(zone, particle);
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "batch_updated");
        response.addProperty("zone", zoneName);
        response.addProperty("updated", updatedCount);

        return response;
    }

    private JsonObject handleUpdateEntity(JsonObject message) {
        String zoneName = message.get("zone").getAsString();
        String entityId = message.get("id").getAsString();

        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        EntityPoolManager pool = plugin.getEntityPoolManager();
        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);

        if (message.has("x") && message.has("y") && message.has("z")) {
            double localX = message.get("x").getAsDouble();
            double localY = message.get("y").getAsDouble();
            double localZ = message.get("z").getAsDouble();

            Location worldLoc = zone.localToWorld(localX, localY, localZ);
            pool.updateEntityPosition(zoneName, entityId, worldLoc.getX(), worldLoc.getY(), worldLoc.getZ());
        }

        if (message.has("scale")) {
            float scale = message.get("scale").getAsFloat();
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
        String zoneName = message.get("zone").getAsString();
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
        String zoneName = message.get("zone").getAsString();
        plugin.getEntityPoolManager().cleanupZone(zoneName);

        JsonObject response = new JsonObject();
        response.addProperty("type", "zone_cleaned");
        response.addProperty("zone", zoneName);

        return response;
    }

    private void spawnParticle(VisualizationZone zone, JsonObject particle) {
        String particleName = particle.get("particle").getAsString();
        double localX = particle.has("x") ? particle.get("x").getAsDouble() : 0.5;
        double localY = particle.has("y") ? particle.get("y").getAsDouble() : 0.5;
        double localZ = particle.has("z") ? particle.get("z").getAsDouble() : 0.5;
        int count = particle.has("count") ? particle.get("count").getAsInt() : 10;

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

    private JsonObject zoneToJson(VisualizationZone zone) {
        JsonObject json = new JsonObject();
        json.addProperty("name", zone.getName());
        json.addProperty("id", zone.getId().toString());
        json.addProperty("world", zone.getWorld().getName());

        JsonObject origin = new JsonObject();
        origin.addProperty("x", zone.getOrigin().getX());
        origin.addProperty("y", zone.getOrigin().getY());
        origin.addProperty("z", zone.getOrigin().getZ());
        json.add("origin", origin);

        JsonObject size = new JsonObject();
        size.addProperty("x", zone.getSize().getX());
        size.addProperty("y", zone.getSize().getY());
        size.addProperty("z", zone.getSize().getZ());
        json.add("size", size);

        json.addProperty("rotation", zone.getRotation());

        return json;
    }

    private JsonObject createError(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "error");
        error.addProperty("message", message);
        return error;
    }
}
