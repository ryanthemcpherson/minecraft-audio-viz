package com.audioviz.protocol;

import com.audioviz.AudioVizPlugin;
import com.audioviz.effects.BeatEffectConfig;
import com.audioviz.effects.BeatEventManager;
import com.audioviz.effects.BeatType;
import com.audioviz.entities.EntityPoolManager;
import com.audioviz.particles.ParticleVisualizationManager;
import com.audioviz.patterns.AudioState;
import com.audioviz.render.RendererBackendType;
import com.audioviz.render.RendererRegistry;
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
            case "set_zone_config" -> handleSetZoneConfig(message);
            case "set_entity_glow" -> handleSetEntityGlow(message);
            case "set_entity_brightness" -> handleSetEntityBrightness(message);
            case "set_render_mode" -> handleSetRenderMode(message);
            case "set_renderer_backend" -> handleSetRendererBackend(message);
            case "renderer_capabilities", "get_renderer_capabilities" -> handleRendererCapabilities(message);
            case "set_hologram_config" -> handleSetHologramConfig(message);
            case "set_particle_viz_config" -> handleSetParticleVizConfig(message);
            case "set_particle_effect" -> handleSetParticleEffect(message);
            case "set_particle_config" -> handleSetParticleConfig(message);
            case "audio_state" -> handleAudioState(message);
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
        if (!message.has("zone")) {
            return createError("Missing required field: zone");
        }
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
        if (!message.has("zone") || !message.has("id")) {
            return createError("Missing required field: zone or id");
        }
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
        if (!message.has("zone") || !message.has("visible")) {
            return createError("Missing required field: zone or visible");
        }
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
        if (!message.has("zone")) {
            return createError("Missing required field: zone");
        }
        String zoneName = message.get("zone").getAsString();
        plugin.getEntityPoolManager().cleanupZone(zoneName);
        plugin.getParticleVisualizationManager().removeZoneConfig(zoneName);
        plugin.getRendererRegistry().removeZone(zoneName);

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

    /**
     * Handle zone configuration updates from admin panel.
     * Allows changing entity count, block type, brightness, interpolation, etc.
     */
    private JsonObject handleSetZoneConfig(JsonObject message) {
        if (!message.has("zone") || !message.has("config")) {
            return createError("Missing required field: zone or config");
        }
        String zoneName = message.get("zone").getAsString();
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

        // Update entity pool if count changed (pool manager handles incremental updates)
        int entityCount = config.has("entity_count") ? config.get("entity_count").getAsInt() : 16;
        String blockType = config.has("block_type") ? config.get("block_type").getAsString() : "SEA_LANTERN";

        Material material = Material.matchMaterial(blockType);
        if (material == null) material = Material.SEA_LANTERN;

        // Pool manager now handles incremental add/remove - no cleanup needed
        plugin.getEntityPoolManager().initializeBlockPool(zoneName, entityCount, material);

        // Update display properties
        if (config.has("brightness")) {
            int brightness = config.get("brightness").getAsInt();
            plugin.getEntityPoolManager().setZoneBrightness(zoneName, brightness);
        }

        if (config.has("interpolation")) {
            int interpolation = config.get("interpolation").getAsInt();
            plugin.getEntityPoolManager().setZoneInterpolation(zoneName, interpolation);
        }

        // Store config for reference
        // These are forwarded to Python processor which uses them for pattern generation
        JsonObject response = new JsonObject();
        response.addProperty("type", "zone_config_updated");
        response.addProperty("zone", zoneName);
        response.add("config", config);

        return response;
    }

    /**
     * Handle glow effect for specific entities.
     */
    private JsonObject handleSetEntityGlow(JsonObject message) {
        if (!message.has("zone") || !message.has("glow")) {
            return createError("Missing required field: zone or glow");
        }
        String zoneName = message.get("zone").getAsString();
        boolean glow = message.get("glow").getAsBoolean();

        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        EntityPoolManager pool = plugin.getEntityPoolManager();

        // If entity IDs provided, update those; otherwise update all
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

    /**
     * Handle per-entity brightness adjustments.
     */
    private JsonObject handleSetEntityBrightness(JsonObject message) {
        if (!message.has("zone") || !message.has("brightness")) {
            return createError("Missing required field: zone or brightness");
        }
        String zoneName = message.get("zone").getAsString();
        int brightness = message.get("brightness").getAsInt();

        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        EntityPoolManager pool = plugin.getEntityPoolManager();

        // If entity IDs provided, update those; otherwise update all
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

    /**
     * Handle render mode changes (entities, particles, or hybrid).
     * Used for Bedrock compatibility - particles work with Geyser, Display Entities don't.
     */
    private JsonObject handleSetRenderMode(JsonObject message) {
        if (!message.has("zone") || !message.has("mode")) {
            return createError("Missing required field: zone or mode");
        }
        String zoneName = message.get("zone").getAsString();
        String mode = message.get("mode").getAsString();

        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        // Valid modes: "entities", "particles", "hybrid"
        if (!mode.equals("entities") && !mode.equals("particles") && !mode.equals("hybrid")) {
            return createError("Invalid render mode: " + mode + ". Use 'entities', 'particles', or 'hybrid'");
        }

        applyLegacyModeToRuntime(zoneName, mode);

        // Keep renderer backend registry in sync with legacy render mode control.
        RendererRegistry rendererRegistry = plugin.getRendererRegistry();
        if (mode.equals("particles")) {
            rendererRegistry.setZoneBackends(
                zoneName,
                RendererBackendType.PARTICLES,
                RendererBackendType.DISPLAY_ENTITIES
            );
        } else {
            // "entities" and "hybrid" are both anchored on display entities.
            rendererRegistry.setZoneBackends(
                zoneName,
                RendererBackendType.DISPLAY_ENTITIES,
                RendererBackendType.PARTICLES
            );
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "render_mode_updated");
        response.addProperty("zone", zoneName);
        response.addProperty("mode", mode);
        response.addProperty("active_backend", rendererRegistry.getActiveBackend(zoneName).key());
        response.addProperty("fallback_backend", rendererRegistry.getFallbackBackend(zoneName).key());

        return response;
    }

    /**
     * New renderer backend selector for contract-driven rendering.
     * Backends are stable keys: display_entities, particles, hologram.
     */
    private JsonObject handleSetRendererBackend(JsonObject message) {
        if (!message.has("zone") || !message.has("backend")) {
            return createError("Missing required field: zone or backend");
        }

        String zoneName = message.get("zone").getAsString();
        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        RendererBackendType backendType = RendererBackendType.fromKey(message.get("backend").getAsString());
        if (backendType == null) {
            return createError("Invalid renderer backend");
        }

        RendererRegistry rendererRegistry = plugin.getRendererRegistry();
        if (!rendererRegistry.isBackendSupported(backendType)) {
            return createError("Backend not supported on this server: " + backendType.key());
        }

        RendererBackendType fallbackType = RendererBackendType.DISPLAY_ENTITIES;
        if (message.has("fallback_backend")) {
            RendererBackendType requestedFallback = RendererBackendType.fromKey(
                message.get("fallback_backend").getAsString()
            );
            if (requestedFallback != null) {
                fallbackType = requestedFallback;
            }
        }

        rendererRegistry.setZoneBackends(zoneName, backendType, fallbackType);
        RendererBackendType active = rendererRegistry.getActiveBackend(zoneName);
        RendererBackendType effective = rendererRegistry.getEffectiveBackend(zoneName);
        boolean usingFallback = active != effective;

        // Apply what is currently executable in runtime.
        if (effective == RendererBackendType.PARTICLES) {
            applyLegacyModeToRuntime(zoneName, "particles");
        } else if (effective == RendererBackendType.DISPLAY_ENTITIES) {
            applyLegacyModeToRuntime(zoneName, "entities");
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "renderer_backend_updated");
        response.addProperty("zone", zoneName);
        response.addProperty("backend", active.key());
        response.addProperty("fallback_backend", rendererRegistry.getFallbackBackend(zoneName).key());
        response.addProperty("effective_backend", effective.key());
        response.addProperty("using_fallback", usingFallback);

        if (active == RendererBackendType.HOLOGRAM && usingFallback) {
            response.addProperty("note", "Hologram backend selected but currently falling back");
        }

        return response;
    }

    private JsonObject handleRendererCapabilities(JsonObject message) {
        String zoneName = message.has("zone") ? message.get("zone").getAsString() : "main";
        RendererRegistry rendererRegistry = plugin.getRendererRegistry();

        JsonObject response = new JsonObject();
        response.addProperty("type", "renderer_capabilities");
        response.addProperty("zone", zoneName);

        JsonArray supported = new JsonArray();
        for (String key : rendererRegistry.getSupportedBackendKeys()) {
            supported.add(key);
        }
        response.add("supported_backends", supported);

        JsonArray experimental = new JsonArray();
        for (String key : rendererRegistry.getExperimentalBackendKeys()) {
            experimental.add(key);
        }
        response.add("experimental_backends", experimental);

        response.addProperty("active_backend", rendererRegistry.getActiveBackend(zoneName).key());
        response.addProperty("fallback_backend", rendererRegistry.getFallbackBackend(zoneName).key());

        JsonObject providers = new JsonObject();
        JsonObject hologramProvider = new JsonObject();
        hologramProvider.addProperty("available", rendererRegistry.isHologramProviderAvailable());
        hologramProvider.addProperty("provider", rendererRegistry.getHologramProviderName());
        hologramProvider.addProperty("implemented", rendererRegistry.isHologramBackendImplemented());
        providers.add("hologram", hologramProvider);
        response.add("providers", providers);

        return response;
    }

    private JsonObject handleSetHologramConfig(JsonObject message) {
        if (!message.has("zone") || !message.has("config")) {
            return createError("Missing required field: zone or config");
        }
        String zoneName = message.get("zone").getAsString();
        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        JsonObject config = message.getAsJsonObject("config");
        plugin.getRendererRegistry().setHologramConfig(zoneName, config);

        JsonObject response = new JsonObject();
        response.addProperty("type", "hologram_config_updated");
        response.addProperty("zone", zoneName);
        response.add("config", plugin.getRendererRegistry().getHologramConfig(zoneName));
        return response;
    }

    private void applyLegacyModeToRuntime(String zoneName, String mode) {
        ParticleVisualizationManager particleViz = plugin.getParticleVisualizationManager();
        particleViz.setRenderMode(zoneName, mode);

        EntityPoolManager pool = plugin.getEntityPoolManager();
        if (mode.equals("particles")) {
            for (String entityId : pool.getEntityIds(zoneName)) {
                pool.setEntityVisible(zoneName, entityId, false);
            }
        } else if (mode.equals("entities") || mode.equals("hybrid")) {
            for (String entityId : pool.getEntityIds(zoneName)) {
                pool.setEntityVisible(zoneName, entityId, true);
            }
        }
    }

    /**
     * Handle particle visualization configuration.
     * Configures particle type, density, color mode, etc. for Bedrock mode.
     */
    private JsonObject handleSetParticleVizConfig(JsonObject message) {
        if (!message.has("zone") || !message.has("config")) {
            return createError("Missing required field: zone or config");
        }
        String zoneName = message.get("zone").getAsString();

        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        ParticleVisualizationManager particleViz = plugin.getParticleVisualizationManager();
        ParticleVisualizationManager.ParticleVizConfig config = particleViz.getOrCreateConfig(zoneName);

        JsonObject configJson = message.getAsJsonObject("config");

        if (configJson.has("particle_type")) {
            config.setParticleType(configJson.get("particle_type").getAsString());
        }

        if (configJson.has("density")) {
            config.setDensity(configJson.get("density").getAsInt());
        }

        if (configJson.has("color_mode")) {
            config.setColorMode(configJson.get("color_mode").getAsString());
        }

        if (configJson.has("fixed_color")) {
            config.setFixedColor(configJson.get("fixed_color").getAsString());
        }

        if (configJson.has("particle_size")) {
            config.setParticleSize(configJson.get("particle_size").getAsFloat());
        }

        if (configJson.has("trail")) {
            config.setTrail(configJson.get("trail").getAsBoolean());
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "particle_viz_config_updated");
        response.addProperty("zone", zoneName);
        response.add("config", configJson);

        return response;
    }

    /**
     * Handle setting beat-triggered particle effects for a zone.
     * Enables/disables effects like particle_burst, screen_shake, lightning.
     */
    private JsonObject handleSetParticleEffect(JsonObject message) {
        if (!message.has("zone") || !message.has("effect")) {
            return createError("Missing required field: zone or effect");
        }
        String zoneName = message.get("zone").getAsString();
        String effectId = message.get("effect").getAsString();
        boolean enabled = message.has("enabled") ? message.get("enabled").getAsBoolean() : true;

        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        BeatEventManager beatManager = plugin.getBeatEventManager();
        BeatEffectConfig config = beatManager.getZoneConfig(zoneName);
        if (config == null) {
            config = new BeatEffectConfig();
            beatManager.setZoneConfig(zoneName, config);
        }

        // Get the effect
        var effect = beatManager.get(effectId);
        if (effect == null) {
            return createError("Unknown effect: " + effectId);
        }

        // Enable/disable the effect for beat type
        BeatType beatType = message.has("beat_type") ?
            BeatType.valueOf(message.get("beat_type").getAsString().toUpperCase()) :
            BeatType.BEAT;

        if (enabled) {
            config.addEffect(beatType, effect);
        } else {
            config.removeEffect(beatType, effect);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "particle_effect_updated");
        response.addProperty("zone", zoneName);
        response.addProperty("effect", effectId);
        response.addProperty("enabled", enabled);

        return response;
    }

    /**
     * Handle particle effect configuration (threshold, cooldown).
     */
    private JsonObject handleSetParticleConfig(JsonObject message) {
        if (!message.has("zone")) {
            return createError("Missing required field: zone");
        }
        String zoneName = message.get("zone").getAsString();

        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        BeatEventManager beatManager = plugin.getBeatEventManager();
        BeatEffectConfig config = beatManager.getZoneConfig(zoneName);
        if (config == null) {
            config = new BeatEffectConfig();
            beatManager.setZoneConfig(zoneName, config);
        }

        // Update threshold if provided
        if (message.has("threshold")) {
            double threshold = message.get("threshold").getAsDouble();
            config.setThreshold(BeatType.BEAT, threshold);
        }

        // Update cooldown if provided
        if (message.has("cooldown_ms")) {
            long cooldown = message.get("cooldown_ms").getAsLong();
            config.setCooldown(BeatType.BEAT, cooldown);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "particle_config_updated");
        response.addProperty("zone", zoneName);

        return response;
    }

    /**
     * Handle audio state updates - triggers beat effects when beats are detected.
     */
    private JsonObject handleAudioState(JsonObject message) {
        if (!message.has("zone")) {
            return createError("Missing required field: zone");
        }
        String zoneName = message.get("zone").getAsString();

        boolean isBeat = message.has("is_beat") && message.get("is_beat").getAsBoolean();
        double beatIntensity = message.has("beat_intensity") ? message.get("beat_intensity").getAsDouble() : 0.0;

        // Trigger beat effects if this is a beat
        if (isBeat && beatIntensity > 0) {
            BeatEventManager beatManager = plugin.getBeatEventManager();
            beatManager.processBeat(zoneName, BeatType.BEAT, beatIntensity);
        }

        // Update particle visualization with audio state
        if (message.has("bands")) {
            JsonArray bandsJson = message.getAsJsonArray("bands");
            double[] bands = new double[bandsJson.size()];
            for (int i = 0; i < bands.length; i++) {
                bands[i] = bandsJson.get(i).getAsDouble();
            }
            double amplitude = message.has("amplitude") ? message.get("amplitude").getAsDouble() : 0.0;
            long frame = message.has("frame") ? message.get("frame").getAsLong() : 0;

            AudioState audioState = new AudioState(bands, amplitude, isBeat, beatIntensity, frame);
            plugin.getParticleVisualizationManager().updateAudioState(audioState);
        }

        // Silent response (high-frequency message)
        JsonObject response = new JsonObject();
        response.addProperty("type", "ok");
        return response;
    }

    private JsonObject createError(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "error");
        error.addProperty("message", message);
        return error;
    }
}
