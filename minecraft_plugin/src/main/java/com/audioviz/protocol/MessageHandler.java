package com.audioviz.protocol;

import com.audioviz.AudioVizPlugin;
import com.audioviz.bedrock.BedrockSupport;
import com.audioviz.decorators.BannerConfig;
import com.audioviz.decorators.DJInfo;
import com.audioviz.effects.BeatEffectConfig;
import com.audioviz.effects.BeatEventManager;
import com.audioviz.effects.BeatType;
import com.audioviz.entities.EntityPoolManager;
import com.audioviz.entities.EntityUpdate;
import com.audioviz.particles.ParticleVisualizationManager;
import com.audioviz.patterns.AudioState;
import com.audioviz.render.RendererBackendType;
import com.audioviz.render.RendererRegistry;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageManager;
import com.audioviz.stages.StageTemplate;
import com.audioviz.stages.StageZoneConfig;
import com.audioviz.stages.StageZoneRole;
import com.audioviz.voice.VoicechatIntegration;
import com.audioviz.zones.VisualizationZone;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Handles WebSocket protocol messages and dispatches to appropriate handlers.
 */
public class MessageHandler {

    private final AudioVizPlugin plugin;
    private final Map<String, Long> lastBeatTimestampByZone = new ConcurrentHashMap<>();

    /** Valid zone/stage name: 1-64 alphanumeric characters, underscores, and hyphens. */
    private static final Pattern VALID_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");
    private static final int MAX_NAME_LENGTH = 64;

    public MessageHandler(AudioVizPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Validate a zone or stage name from WebSocket input.
     * Must be non-null, non-empty, max 64 chars, and match [a-zA-Z0-9_-]+.
     */
    private static boolean isValidZoneName(String name) {
        return name != null
            && !name.isEmpty()
            && name.length() <= MAX_NAME_LENGTH
            && VALID_NAME_PATTERN.matcher(name).matches();
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
            // Stage management
            case "get_stages" -> handleGetStages();
            case "get_stage" -> handleGetStage(message);
            case "create_stage" -> handleCreateStage(message);
            case "delete_stage" -> handleDeleteStage(message);
            case "activate_stage" -> handleActivateStage(message);
            case "deactivate_stage" -> handleDeactivateStage(message);
            case "update_stage" -> handleUpdateStage(message);
            case "set_stage_zone_config" -> handleSetStageZoneConfig(message);
            case "get_stage_templates" -> handleGetStageTemplates();
            // DJ info
            case "dj_info" -> handleDjInfo(message);
            // Banner config
            case "banner_config" -> handleBannerConfig(message);
            // Voice chat
            case "voice_audio" -> handleVoiceAudio(message);
            case "voice_config" -> handleVoiceConfig(message);
            case "get_voice_status" -> handleGetVoiceStatus();
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

                // Add transformation if scale provided (clamped to [0, 4])
                if (entity.has("scale")) {
                    float scale = InputSanitizer.sanitizeScale(entity.get("scale").getAsFloat());
                    float rotation = InputSanitizer.sanitizeRotation(
                        entity.has("rotation") ? entity.get("rotation").getAsFloat() : 0);
                    builder.transformation(new Transformation(
                        new Vector3f(0, 0, 0),
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

                batchUpdates.add(builder.build());
                updatedCount++;
            }

            // Single scheduler call for ALL entity updates
            if (!batchUpdates.isEmpty()) {
                pool.batchUpdateEntities(zoneName, batchUpdates);
            }

            // Handle visibility separately (needs scale-to-zero, different from batch)
            // Collect all visibility changes and apply in a single scheduler call
            List<Map.Entry<String, Boolean>> visibilityChanges = new ArrayList<>();
            for (JsonElement elem : entities) {
                JsonObject entity = elem.getAsJsonObject();
                if (entity.has("visible") && entity.has("id")) {
                    visibilityChanges.add(Map.entry(
                        entity.get("id").getAsString(),
                        entity.get("visible").getAsBoolean()));
                }
            }
            if (!visibilityChanges.isEmpty()) {
                pool.batchSetVisible(zoneName, visibilityChanges);
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
        json.addProperty("glow_on_beat", zone.isGlowOnBeat());
        json.addProperty("dynamic_brightness", zone.isDynamicBrightness());

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

        // Update glow_on_beat setting
        if (config.has("glow_on_beat")) {
            zone.setGlowOnBeat(config.get("glow_on_beat").getAsBoolean());
        }

        // Update dynamic_brightness setting
        if (config.has("dynamic_brightness")) {
            zone.setDynamicBrightness(config.get("dynamic_brightness").getAsBoolean());
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
        if (!isValidZoneName(zoneName)) {
            return createError("Invalid zone name");
        }
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
        if (!isValidZoneName(zoneName)) {
            return createError("Invalid zone name");
        }
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
        if (!isValidZoneName(zoneName)) {
            return createError("Invalid zone name");
        }
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
        if (!isValidZoneName(zoneName)) {
            return createError("Invalid zone name");
        }
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

        // Bedrock support status
        BedrockSupport bedrockSupport = plugin.getBedrockSupport();
        JsonObject bedrock = new JsonObject();
        bedrock.addProperty("geyser_present", bedrockSupport.isGeyserPresent());
        bedrock.addProperty("floodgate_present", bedrockSupport.isFloodgatePresent());
        bedrock.addProperty("geyser_display_entity", bedrockSupport.isGeyserDisplayEntityPresent());
        bedrock.addProperty("particle_fallback_active", bedrockSupport.needsParticleFallback());
        bedrock.addProperty("bedrock_players_online", bedrockSupport.getBedrockPlayers().size());
        response.add("bedrock", bedrock);

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
        BeatType beatType;
        if (message.has("beat_type")) {
            try {
                beatType = BeatType.valueOf(message.get("beat_type").getAsString().toUpperCase());
            } catch (IllegalArgumentException e) {
                return createError("Unknown beat type: " + message.get("beat_type").getAsString());
            }
        } else {
            beatType = BeatType.BEAT;
        }

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

        boolean explicitBeat = message.has("is_beat") && message.get("is_beat").getAsBoolean();
        double explicitBeatIntensity = InputSanitizer.sanitizeDouble(
            message.has("beat_intensity") ? message.get("beat_intensity").getAsDouble() : 0.0,
            0.0, 1.0, 0.0);
        double bpm = InputSanitizer.sanitizeDouble(
            message.has("bpm") ? message.get("bpm").getAsDouble() : 0.0,
            0.0, 300.0, 0.0);
        double tempoConfidence = InputSanitizer.sanitizeDouble(
            message.has("tempo_confidence") ? message.get("tempo_confidence").getAsDouble()
                : (message.has("tempo_conf") ? message.get("tempo_conf").getAsDouble() : 0.0),
            0.0, 1.0, 0.0);
        double beatPhase = InputSanitizer.sanitizeDouble(
            message.has("beat_phase") ? message.get("beat_phase").getAsDouble() : 0.0,
            0.0, 1.0, 0.0);

        BeatProjectionUtil.BeatProjection projection = BeatProjectionUtil.projectBeat(
            zoneName, explicitBeat, explicitBeatIntensity, bpm, tempoConfidence, beatPhase,
            lastBeatTimestampByZone);
        boolean isBeat = projection.isBeat();
        double beatIntensity = projection.beatIntensity();

        // Trigger beat effects if this is a beat
        if (isBeat && beatIntensity > 0) {
            BeatEventManager beatManager = plugin.getBeatEventManager();
            beatManager.processBeat(zoneName, BeatType.BEAT, beatIntensity);
        }

        // Update particle visualization with audio state
        if (message.has("bands")) {
            JsonArray bandsJson = message.getAsJsonArray("bands");
            int bandCount = Math.min(bandsJson.size(), 10); // Cap array size
            double[] bands = new double[bandCount];
            for (int i = 0; i < bands.length; i++) {
                bands[i] = InputSanitizer.sanitizeBandValue(bandsJson.get(i).getAsDouble());
            }
            double amplitude = InputSanitizer.sanitizeAmplitude(
                message.has("amplitude") ? message.get("amplitude").getAsDouble() : 0.0);
            long frame = message.has("frame") ? message.get("frame").getAsLong() : 0;

            AudioState audioState = new AudioState(
                bands, amplitude, isBeat, beatIntensity, tempoConfidence, beatPhase, frame);
            plugin.getParticleVisualizationManager().updateAudioState(audioState);

            // Forward audio state to decorator manager
            if (plugin.getDecoratorManager() != null) {
                plugin.getDecoratorManager().updateAudioState(audioState);
            }
        }

        // Silent response (high-frequency message)
        JsonObject response = new JsonObject();
        response.addProperty("type", "ok");
        return response;
    }

    // ========== DJ Info Handler ==========

    private JsonObject handleDjInfo(JsonObject message) {
        String djName = message.has("dj_name") ? message.get("dj_name").getAsString() : "";
        String djId = message.has("dj_id") ? message.get("dj_id").getAsString() : "";
        double bpm = message.has("bpm") ? message.get("bpm").getAsDouble() : 0.0;
        boolean isActive = message.has("is_active") ? message.get("is_active").getAsBoolean() : true;

        DJInfo djInfo = new DJInfo(djName, djId, bpm, isActive, System.currentTimeMillis());

        if (plugin.getDecoratorManager() != null) {
            plugin.getDecoratorManager().updateDJInfo(djInfo);
        }

        plugin.getLogger().info("DJ info received: " + djName + " (BPM: " + String.format("%.0f", bpm) + ")");

        JsonObject response = new JsonObject();
        response.addProperty("type", "dj_info_received");
        response.addProperty("dj_name", djName);
        return response;
    }

    // ========== Banner Config Handler ==========

    private JsonObject handleBannerConfig(JsonObject message) {
        BannerConfig bannerConfig = BannerConfig.fromJson(message);

        if (plugin.getDecoratorManager() != null) {
            plugin.getDecoratorManager().updateBannerConfig(bannerConfig);
        }

        plugin.getLogger().info("Banner config received: " + bannerConfig);

        JsonObject response = new JsonObject();
        response.addProperty("type", "banner_config_received");
        return response;
    }

    // ========== Stage Handlers ==========

    private JsonObject handleGetStages() {
        StageManager stageManager = plugin.getStageManager();
        JsonObject response = new JsonObject();
        response.addProperty("type", "stages");

        JsonArray stagesArray = new JsonArray();
        for (Stage stage : stageManager.getAllStages()) {
            stagesArray.add(stageToJson(stage));
        }
        response.add("stages", stagesArray);
        response.addProperty("count", stageManager.getStageCount());

        return response;
    }

    private JsonObject handleGetStage(JsonObject message) {
        if (!message.has("name")) {
            return createError("Missing required field: name");
        }
        String name = message.get("name").getAsString();
        Stage stage = plugin.getStageManager().getStage(name);

        if (stage == null) {
            return createError("Stage not found: " + name);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "stage");
        response.add("stage", stageToJson(stage));

        return response;
    }

    private JsonObject handleCreateStage(JsonObject message) {
        if (!message.has("name") || !message.has("template")) {
            return createError("Missing required field: name or template");
        }
        String name = message.get("name").getAsString();
        if (!isValidZoneName(name)) {
            plugin.getLogger().warning("Invalid stage name in create_stage: " + name);
            return createError("Invalid stage name");
        }
        String templateName = message.get("template").getAsString();
        if (!isValidZoneName(templateName)) {
            plugin.getLogger().warning("Invalid template name in create_stage: " + templateName);
            return createError("Invalid template name");
        }

        StageManager stageManager = plugin.getStageManager();

        if (stageManager.stageExists(name)) {
            return createError("Stage already exists: " + name);
        }

        if (stageManager.getTemplate(templateName) == null) {
            return createError("Unknown template: " + templateName);
        }

        // Parse anchor location
        Location anchor;
        if (message.has("anchor")) {
            JsonObject anchorJson = message.getAsJsonObject("anchor");
            String worldName = anchorJson.has("world") ? anchorJson.get("world").getAsString() : "world";
            org.bukkit.World world = plugin.getServer().getWorld(worldName);
            if (world == null) {
                return createError("World not found: " + worldName);
            }
            anchor = new Location(world,
                anchorJson.has("x") ? anchorJson.get("x").getAsDouble() : 0,
                anchorJson.has("y") ? anchorJson.get("y").getAsDouble() : 64,
                anchorJson.has("z") ? anchorJson.get("z").getAsDouble() : 0);
        } else {
            // Default to world spawn
            org.bukkit.World world = plugin.getServer().getWorlds().get(0);
            anchor = world.getSpawnLocation();
        }

        Stage stage = stageManager.createStage(name, anchor, templateName);
        if (stage == null) {
            return createError("Failed to create stage");
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "stage_created");
        response.add("stage", stageToJson(stage));

        return response;
    }

    private JsonObject handleDeleteStage(JsonObject message) {
        if (!message.has("name")) {
            return createError("Missing required field: name");
        }
        String name = message.get("name").getAsString();
        if (!isValidZoneName(name)) {
            plugin.getLogger().warning("Invalid stage name in delete_stage: " + name);
            return createError("Invalid stage name");
        }

        if (!plugin.getStageManager().deleteStage(name)) {
            return createError("Stage not found: " + name);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "stage_deleted");
        response.addProperty("name", name);

        return response;
    }

    private JsonObject handleActivateStage(JsonObject message) {
        if (!message.has("name")) {
            return createError("Missing required field: name");
        }
        String name = message.get("name").getAsString();
        Stage stage = plugin.getStageManager().getStage(name);

        if (stage == null) {
            return createError("Stage not found: " + name);
        }

        plugin.getStageManager().activateStage(stage);

        JsonObject response = new JsonObject();
        response.addProperty("type", "stage_activated");
        response.addProperty("name", name);

        return response;
    }

    private JsonObject handleDeactivateStage(JsonObject message) {
        if (!message.has("name")) {
            return createError("Missing required field: name");
        }
        String name = message.get("name").getAsString();
        Stage stage = plugin.getStageManager().getStage(name);

        if (stage == null) {
            return createError("Stage not found: " + name);
        }

        plugin.getStageManager().deactivateStage(stage);

        JsonObject response = new JsonObject();
        response.addProperty("type", "stage_deactivated");
        response.addProperty("name", name);

        return response;
    }

    private JsonObject handleUpdateStage(JsonObject message) {
        if (!message.has("name")) {
            return createError("Missing required field: name");
        }
        String name = message.get("name").getAsString();
        StageManager stageManager = plugin.getStageManager();
        Stage stage = stageManager.getStage(name);

        if (stage == null) {
            return createError("Stage not found: " + name);
        }

        // Move anchor if provided
        if (message.has("anchor")) {
            JsonObject anchorJson = message.getAsJsonObject("anchor");
            String worldName = anchorJson.has("world") ? anchorJson.get("world").getAsString()
                : stage.getAnchor().getWorld().getName();
            org.bukkit.World world = plugin.getServer().getWorld(worldName);
            if (world == null) {
                return createError("World not found: " + worldName);
            }
            Location newAnchor = new Location(world,
                anchorJson.has("x") ? anchorJson.get("x").getAsDouble() : stage.getAnchor().getX(),
                anchorJson.has("y") ? anchorJson.get("y").getAsDouble() : stage.getAnchor().getY(),
                anchorJson.has("z") ? anchorJson.get("z").getAsDouble() : stage.getAnchor().getZ());
            stageManager.moveStage(stage, newAnchor);
        }

        // Rotate if provided
        if (message.has("rotation")) {
            float rotation = message.get("rotation").getAsFloat();
            stageManager.rotateStage(stage, rotation);
        }

        // Add role if provided
        if (message.has("add_role")) {
            String roleName = message.get("add_role").getAsString();
            try {
                StageZoneRole role = StageZoneRole.valueOf(roleName.toUpperCase());
                stageManager.addRoleToStage(stage, role);
            } catch (IllegalArgumentException e) {
                return createError("Unknown zone role: " + roleName);
            }
        }

        // Remove role if provided
        if (message.has("remove_role")) {
            String roleName = message.get("remove_role").getAsString();
            try {
                StageZoneRole role = StageZoneRole.valueOf(roleName.toUpperCase());
                stageManager.removeRoleFromStage(stage, role);
            } catch (IllegalArgumentException e) {
                return createError("Unknown zone role: " + roleName);
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "stage_updated");
        response.add("stage", stageToJson(stage));

        return response;
    }

    private JsonObject handleSetStageZoneConfig(JsonObject message) {
        if (!message.has("stage") || !message.has("role") || !message.has("config")) {
            return createError("Missing required field: stage, role, or config");
        }
        String stageName = message.get("stage").getAsString();
        if (!isValidZoneName(stageName)) {
            plugin.getLogger().warning("Invalid stage name in set_stage_zone_config: " + stageName);
            return createError("Invalid stage name");
        }
        String roleName = message.get("role").getAsString();

        Stage stage = plugin.getStageManager().getStage(stageName);
        if (stage == null) {
            return createError("Stage not found: " + stageName);
        }

        StageZoneRole role;
        try {
            role = StageZoneRole.valueOf(roleName.toUpperCase());
        } catch (IllegalArgumentException e) {
            return createError("Unknown zone role: " + roleName);
        }

        if (!stage.getRoleToZone().containsKey(role)) {
            return createError("Stage '" + stageName + "' does not have role: " + roleName);
        }

        JsonObject configJson = message.getAsJsonObject("config");
        StageZoneConfig config = stage.getOrCreateConfig(role);

        if (configJson.has("pattern")) {
            config.setPattern(configJson.get("pattern").getAsString());
        }
        if (configJson.has("entity_count")) {
            config.setEntityCount(configJson.get("entity_count").getAsInt());
        }
        if (configJson.has("render_mode")) {
            config.setRenderMode(configJson.get("render_mode").getAsString());
        }
        if (configJson.has("block_type")) {
            config.setBlockType(configJson.get("block_type").getAsString());
        }
        if (configJson.has("brightness")) {
            config.setBrightness(configJson.get("brightness").getAsInt());
        }
        if (configJson.has("glow_on_beat")) {
            config.setGlowOnBeat(configJson.get("glow_on_beat").getAsBoolean());
        }
        if (configJson.has("intensity_multiplier")) {
            config.setIntensityMultiplier(configJson.get("intensity_multiplier").getAsFloat());
        }

        plugin.getStageManager().saveStages();

        // Apply to the actual zone if stage is active
        if (stage.isActive()) {
            String zoneName = stage.getRoleToZone().get(role);
            Material material = Material.matchMaterial(config.getBlockType());
            if (material == null) material = Material.SEA_LANTERN;
            plugin.getEntityPoolManager().initializeBlockPool(zoneName, config.getEntityCount(), material);

            if (config.getBrightness() >= 0) {
                plugin.getEntityPoolManager().setZoneBrightness(zoneName, config.getBrightness());
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "stage_zone_config_updated");
        response.addProperty("stage", stageName);
        response.addProperty("role", role.name());
        response.add("config", stageZoneConfigToJson(config));

        return response;
    }

    private JsonObject handleGetStageTemplates() {
        StageManager stageManager = plugin.getStageManager();
        JsonObject response = new JsonObject();
        response.addProperty("type", "stage_templates");

        JsonArray templatesArray = new JsonArray();
        for (Map.Entry<String, StageTemplate> entry : stageManager.getAllTemplates().entrySet()) {
            StageTemplate template = entry.getValue();
            JsonObject templateJson = new JsonObject();
            templateJson.addProperty("name", template.getName());
            templateJson.addProperty("description", template.getDescription());
            templateJson.addProperty("role_count", template.getRoleCount());
            templateJson.addProperty("estimated_entities", template.getEstimatedEntityCount());

            JsonArray roles = new JsonArray();
            for (StageZoneRole role : template.getRoles()) {
                JsonObject roleJson = new JsonObject();
                roleJson.addProperty("name", role.name());
                roleJson.addProperty("display_name", role.getDisplayName());
                roleJson.addProperty("suggested_pattern", role.getSuggestedPattern());
                roles.add(roleJson);
            }
            templateJson.add("roles", roles);

            templatesArray.add(templateJson);
        }
        response.add("templates", templatesArray);

        return response;
    }

    // ========== Stage JSON Helpers ==========

    private JsonObject stageToJson(Stage stage) {
        JsonObject json = new JsonObject();
        json.addProperty("name", stage.getName());
        json.addProperty("id", stage.getId().toString());
        json.addProperty("template", stage.getTemplateName());
        json.addProperty("active", stage.isActive());
        json.addProperty("rotation", stage.getRotation());

        // Anchor
        Location anchor = stage.getAnchor();
        JsonObject anchorJson = new JsonObject();
        anchorJson.addProperty("world", anchor.getWorld().getName());
        anchorJson.addProperty("x", anchor.getX());
        anchorJson.addProperty("y", anchor.getY());
        anchorJson.addProperty("z", anchor.getZ());
        json.add("anchor", anchorJson);

        // Zones
        JsonObject zonesJson = new JsonObject();
        for (Map.Entry<StageZoneRole, String> entry : stage.getRoleToZone().entrySet()) {
            StageZoneRole role = entry.getKey();
            String zoneName = entry.getValue();

            JsonObject zoneJson = new JsonObject();
            zoneJson.addProperty("zone_name", zoneName);
            zoneJson.addProperty("role", role.name());
            zoneJson.addProperty("display_name", role.getDisplayName());

            // Entity count from pool
            zoneJson.addProperty("entity_count",
                plugin.getEntityPoolManager().getEntityCount(zoneName));

            // Zone config
            StageZoneConfig config = stage.getZoneConfigs().get(role);
            if (config != null) {
                zoneJson.add("config", stageZoneConfigToJson(config));
            }

            zonesJson.add(role.name(), zoneJson);
        }
        json.add("zones", zonesJson);
        json.addProperty("zone_count", stage.getRoleToZone().size());
        json.addProperty("total_entities", stage.getTotalEntityCount());

        return json;
    }

    private JsonObject stageZoneConfigToJson(StageZoneConfig config) {
        JsonObject json = new JsonObject();
        json.addProperty("pattern", config.getPattern());
        json.addProperty("entity_count", config.getEntityCount());
        json.addProperty("render_mode", config.getRenderMode());
        json.addProperty("block_type", config.getBlockType());
        json.addProperty("brightness", config.getBrightness());
        json.addProperty("glow_on_beat", config.isGlowOnBeat());
        json.addProperty("intensity_multiplier", config.getIntensityMultiplier());
        return json;
    }

    // ========== Voice Chat Handlers ==========

    /** Expected byte count for a voice audio frame: 960 samples * 2 bytes per int16 */
    private static final int VOICE_FRAME_BYTES = 1920;

    /** Expected sample count per voice audio frame (20ms at 48kHz mono) */
    private static final int VOICE_FRAME_SAMPLES = 960;

    /**
     * Handle incoming audio frames for voice chat streaming.
     * Supports two codecs:
     * <ul>
     *   <li>{@code "opus"} - data is already Opus-encoded, sent directly to channels</li>
     *   <li>{@code "pcm"} - data is raw PCM (960 int16 samples, little-endian), encoded to Opus first</li>
     * </ul>
     * Defaults to {@code "pcm"} if the codec field is not present (backward compatibility).
     */
    private JsonObject handleVoiceAudio(JsonObject message) {
        VoicechatIntegration voiceChat = plugin.getVoicechatIntegration();
        if (voiceChat == null || !voiceChat.isAvailable()) {
            // Silent drop - voice chat not available, don't spam errors for high-frequency messages
            JsonObject response = new JsonObject();
            response.addProperty("type", "ok");
            return response;
        }

        if (!message.has("data")) {
            return createError("Missing required field: data");
        }

        // Determine codec: "opus" or "pcm" (default for backward compatibility)
        String codec = message.has("codec") ? message.get("codec").getAsString() : "pcm";

        try {
            String base64Data = message.get("data").getAsString();
            byte[] rawBytes = Base64.getDecoder().decode(base64Data);

            if ("opus".equals(codec)) {
                // Data is already Opus-encoded - queue directly
                voiceChat.queueOpusFrame(rawBytes);
            } else {
                // PCM fallback: decode to int16 samples, encode to Opus, then queue
                if (rawBytes.length != VOICE_FRAME_BYTES) {
                    return createError("Invalid PCM voice frame size: expected " + VOICE_FRAME_BYTES +
                            " bytes, got " + rawBytes.length);
                }

                short[] samples = new short[VOICE_FRAME_SAMPLES];
                ByteBuffer buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < VOICE_FRAME_SAMPLES; i++) {
                    samples[i] = buffer.getShort();
                }

                voiceChat.queuePcmFrame(samples);
            }

        } catch (IllegalArgumentException e) {
            return createError("Invalid base64 data: " + e.getMessage());
        }

        // Silent response for high-frequency message
        JsonObject response = new JsonObject();
        response.addProperty("type", "ok");
        return response;
    }

    /**
     * Handle voice chat configuration changes.
     * Updates channel type, distance, zone, and enabled state.
     * Broadcasts the resulting voice_status to all connected WebSocket clients.
     */
    private JsonObject handleVoiceConfig(JsonObject message) {
        VoicechatIntegration voiceChat = plugin.getVoicechatIntegration();
        if (voiceChat == null) {
            return createError("Simple Voice Chat is not installed");
        }

        boolean enabled = message.has("enabled") ? message.get("enabled").getAsBoolean() : true;
        String channelType = message.has("channel_type") ? message.get("channel_type").getAsString() : "static";
        double distance = message.has("distance") ? message.get("distance").getAsDouble() : 100.0;
        String zone = message.has("zone") ? message.get("zone").getAsString() : "main";

        // Validate channel type
        if (!"static".equals(channelType) && !"locational".equals(channelType)) {
            return createError("Invalid channel_type: " + channelType + ". Use 'static' or 'locational'");
        }

        // Validate distance
        if (distance <= 0 || distance > 1000) {
            return createError("Invalid distance: must be between 0 and 1000");
        }

        voiceChat.setConfig(enabled, channelType, distance, zone);

        // Broadcast voice_status to ALL connected WebSocket clients
        JsonObject status = voiceChat.getStatus();
        if (plugin.getWebSocketServer() != null) {
            plugin.getWebSocketServer().broadcast(status);
        }

        // Also return voice_status as the direct response to the sender
        return status;
    }

    private JsonObject handleGetVoiceStatus() {
        VoicechatIntegration voiceChat = plugin.getVoicechatIntegration();
        if (voiceChat == null) {
            JsonObject status = new JsonObject();
            status.addProperty("type", "voice_status");
            status.addProperty("available", false);
            status.addProperty("streaming", false);
            status.addProperty("channel_type", "static");
            status.addProperty("connected_players", 0);
            return status;
        }
        return voiceChat.getStatus();
    }

    private JsonObject createError(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "error");
        error.addProperty("message", message);
        return error;
    }
}
