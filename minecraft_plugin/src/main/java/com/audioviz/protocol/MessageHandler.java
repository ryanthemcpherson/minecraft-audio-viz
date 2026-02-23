package com.audioviz.protocol;

import com.audioviz.AudioVizPlugin;
import com.audioviz.bedrock.BedrockSupport;
import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.bitmap.BitmapPatternManager;
import com.audioviz.bitmap.BitmapRendererBackend;
import com.audioviz.bitmap.composition.CompositionManager;
import com.audioviz.bitmap.effects.ColorPalette;
import com.audioviz.bitmap.effects.EffectsProcessor;
import com.audioviz.bitmap.effects.LayerCompositor;
import com.audioviz.bitmap.gamestate.FireworkPattern;
import com.audioviz.bitmap.media.DJLogoPattern;
import com.audioviz.bitmap.media.ImagePattern;
import com.audioviz.bitmap.text.ChatWallPattern;
import com.audioviz.bitmap.text.CountdownPattern;
import com.audioviz.bitmap.text.MarqueePattern;
import com.audioviz.bitmap.text.TrackDisplayPattern;
import com.audioviz.bitmap.transitions.TransitionManager;
import com.audioviz.decorators.BannerConfig;
import com.audioviz.decorators.DJInfo;
import com.audioviz.effects.BeatEffectConfig;
import com.audioviz.effects.BeatEventManager;
import com.audioviz.effects.BeatType;
import com.audioviz.entities.EntityPoolManager;
import com.audioviz.lighting.AmbientLightManager;
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
import java.util.LinkedHashMap;
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
            case "scan_stage_blocks" -> handleScanStageBlocks(message);
            // DJ info
            case "dj_info" -> handleDjInfo(message);
            // Banner config
            case "banner_config" -> handleBannerConfig(message);
            // Bitmap rendering
            case "init_bitmap" -> handleInitBitmap(message);
            case "teardown_bitmap" -> handleTeardownBitmap(message);
            case "bitmap_frame" -> handleBitmapFrame(message);
            case "set_bitmap_pattern" -> handleSetBitmapPattern(message);
            case "get_bitmap_patterns" -> handleGetBitmapPatterns();
            case "get_bitmap_status" -> handleGetBitmapStatus(message);
            // Bitmap transitions
            case "bitmap_transition" -> handleBitmapTransition(message);
            case "get_bitmap_transitions" -> handleGetBitmapTransitions();
            // Bitmap text/marquee
            case "bitmap_marquee" -> handleBitmapMarquee(message);
            case "bitmap_track_display" -> handleBitmapTrackDisplay(message);
            case "bitmap_countdown" -> handleBitmapCountdown(message);
            case "bitmap_chat" -> handleBitmapChat(message);
            // Bitmap effects
            case "bitmap_effects" -> handleBitmapEffects(message);
            case "bitmap_palette" -> handleBitmapPalette(message);
            case "get_bitmap_palettes" -> handleGetBitmapPalettes();
            // Bitmap layers
            case "bitmap_layer" -> handleBitmapLayer(message);
            // Bitmap game integration
            case "bitmap_firework" -> handleBitmapFirework(message);
            // Bitmap image/media
            case "bitmap_image" -> handleBitmapImage(message);
            case "bitmap_dj_logo" -> handleBitmapDjLogo(message);
            // Bitmap composition
            case "bitmap_composition" -> handleBitmapComposition(message);
            // Voice chat
            case "voice_audio" -> handleVoiceAudio(message);
            case "voice_config" -> handleVoiceConfig(message);
            case "get_voice_status" -> handleGetVoiceStatus();
            // Parity check
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

                // Check visibility — if hidden, force scale to 0 in the same transform
                boolean visible = !entity.has("visible") || entity.get("visible").getAsBoolean();

                // Add transformation if scale provided or entity is hidden (clamped to [0, 4])
                if (entity.has("scale") || !visible) {
                    float scale = !visible ? 0f
                        : InputSanitizer.sanitizeScale(entity.get("scale").getAsFloat());
                    float rotation = InputSanitizer.sanitizeRotation(
                        entity.has("rotation") ? entity.get("rotation").getAsFloat() : 0);
                    float pivotOffset = (1.0f - scale) * 0.5f;
                    builder.transformation(new Transformation(
                        new Vector3f(pivotOffset, pivotOffset, pivotOffset),
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

            // Visibility is now handled inline above — hidden entities get scale=0
            // in the same transform update, eliminating the separate scheduler call
            // that previously caused 2-frame flicker.
        }

        // Process particle effects
        if (message.has("particles")) {
            JsonArray particles = message.getAsJsonArray("particles");

            for (JsonElement elem : particles) {
                JsonObject particle = elem.getAsJsonObject();
                spawnParticle(zone, particle);
            }
        }

        // Forward audio data to bitmap pattern manager (if present in the batch_update).
        // The VJ server embeds bands/amplitude/beat in batch_update messages.
        // The bitmap pattern manager self-ticks at 20 TPS; we just update its audio state.
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

        // Update entity pool only if count or block type changed
        if (config.has("entity_count") || config.has("block_type")) {
            int entityCount = config.has("entity_count") ? config.get("entity_count").getAsInt()
                : plugin.getEntityPoolManager().getEntityCount(zoneName);
            String blockType = config.has("block_type") ? config.get("block_type").getAsString() : "SEA_LANTERN";

            Material material = Material.matchMaterial(blockType);
            if (material == null || !material.isBlock()) material = Material.SEA_LANTERN;

            plugin.getEntityPoolManager().initializeBlockPool(zoneName, entityCount, material);
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

        // Bitmap backend info
        JsonObject bitmap = new JsonObject();
        bitmap.addProperty("implemented", true);
        bitmap.addProperty("active_zones", plugin.getBitmapRenderer() != null ?
            (plugin.getBitmapRenderer().isBitmapZone(zoneName)) : false);
        JsonArray bitmapPatterns = new JsonArray();
        if (plugin.getBitmapPatternManager() != null) {
            for (String id : plugin.getBitmapPatternManager().getPatternIds()) {
                bitmapPatterns.add(id);
            }
        }
        bitmap.add("patterns", bitmapPatterns);
        providers.add("bitmap", bitmap);

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

            // Update bitmap audio state (pattern manager self-ticks at 20 TPS)
            if (plugin.getBitmapPatternManager() != null) {
                plugin.getBitmapPatternManager().updateAudioState(audioState);
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

    // ========== Stage Block Scanner ==========

    /**
     * Scan all non-air blocks in the bounding box around a stage's zones.
     * Returns palette-compressed block data for 3D preview rendering.
     *
     * World block access (getBlockAt) requires the main Bukkit thread, so the
     * actual scan is scheduled via callSyncMethod and this method blocks until
     * the result is ready (up to 15 seconds).
     */
    private JsonObject handleScanStageBlocks(JsonObject message) {
        if (!message.has("stage")) {
            return createError("Missing required field: stage");
        }
        String stageName = message.get("stage").getAsString();
        if (!isValidZoneName(stageName)) {
            return createError("Invalid stage name");
        }

        Stage stage = plugin.getStageManager().getStage(stageName);
        if (stage == null) {
            return createError("Stage not found: " + stageName);
        }

        // Schedule on main thread since world.getBlockAt() requires it
        try {
            return plugin.getServer().getScheduler().callSyncMethod(plugin, () -> {
                return scanStageBlocksSync(stage, stageName);
            }).get(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            plugin.getLogger().warning("Stage block scan timed out for: " + stageName);
            return createError("Stage block scan timed out");
        } catch (Exception e) {
            plugin.getLogger().warning("Stage block scan failed for " + stageName + ": " + e.getMessage());
            return createError("Scan failed: " + e.getMessage());
        }
    }

    /**
     * Perform the actual block scan on the main Bukkit thread.
     */
    private JsonObject scanStageBlocksSync(Stage stage, String stageName) {
        // Compute union bounding box of all zones in the stage
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        org.bukkit.World world = null;

        for (String zoneName : stage.getZoneNames()) {
            VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
            if (zone == null) continue;

            if (world == null) {
                world = zone.getWorld();
            }

            int ox = (int) Math.floor(zone.getOrigin().getX());
            int oy = (int) Math.floor(zone.getOrigin().getY());
            int oz = (int) Math.floor(zone.getOrigin().getZ());
            int ex = ox + (int) Math.ceil(zone.getSize().getX());
            int ey = oy + (int) Math.ceil(zone.getSize().getY());
            int ez = oz + (int) Math.ceil(zone.getSize().getZ());

            minX = Math.min(minX, ox);
            minY = Math.min(minY, oy);
            minZ = Math.min(minZ, oz);
            maxX = Math.max(maxX, ex);
            maxY = Math.max(maxY, ey);
            maxZ = Math.max(maxZ, ez);
        }

        if (world == null) {
            return createError("No zones found for stage: " + stageName);
        }

        // Expand bounding box: +5 XZ, +3 below, +2 above
        minX -= 5;
        minZ -= 5;
        maxX += 5;
        maxZ += 5;
        minY -= 3;
        maxY += 2;

        // Scan blocks - build palette and block array
        LinkedHashMap<String, Integer> palette = new LinkedHashMap<>();
        JsonArray blocksArray = new JsonArray();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Material mat = world.getBlockAt(x, y, z).getType();
                    if (mat == Material.AIR || mat == Material.CAVE_AIR || mat == Material.VOID_AIR) {
                        continue;
                    }

                    String matName = mat.name();
                    int paletteIdx = palette.computeIfAbsent(matName, k -> palette.size());

                    JsonArray block = new JsonArray();
                    block.add(x);
                    block.add(y);
                    block.add(z);
                    block.add(paletteIdx);
                    blocksArray.add(block);
                }
            }
        }

        // Build palette array
        JsonArray paletteArray = new JsonArray();
        for (String matName : palette.keySet()) {
            paletteArray.add(matName);
        }

        // Build bounds object
        JsonObject bounds = new JsonObject();
        bounds.addProperty("minX", minX);
        bounds.addProperty("minY", minY);
        bounds.addProperty("minZ", minZ);
        bounds.addProperty("maxX", maxX);
        bounds.addProperty("maxY", maxY);
        bounds.addProperty("maxZ", maxZ);

        JsonObject response = new JsonObject();
        response.addProperty("type", "stage_blocks");
        response.addProperty("stage", stageName);
        response.add("palette", paletteArray);
        response.add("blocks", blocksArray);
        response.add("bounds", bounds);

        plugin.getLogger().info("Scanned stage '" + stageName + "': " +
            blocksArray.size() + " blocks, " + palette.size() + " materials");

        return response;
    }

    // ========== Bitmap Rendering Handlers ==========

    /**
     * Initialize a bitmap grid for a zone.
     * Expected format:
     * {
     *   "type": "init_bitmap",
     *   "zone": "zone_name",
     *   "width": 32,          // optional — omit for auto-sizing from zone geometry
     *   "height": 16,         // optional — omit for auto-sizing from zone geometry
     *   "pattern": "bmp_spectrum"  // optional, default "bmp_spectrum"
     * }
     */
    private JsonObject handleInitBitmap(JsonObject message) {
        if (!message.has("zone")) {
            return createError("Missing required field: zone");
        }
        String zoneName = message.get("zone").getAsString();
        if (!isValidZoneName(zoneName)) {
            return createError("Invalid zone name");
        }
        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        boolean hasWidth = message.has("width") && message.get("width").getAsInt() > 0;
        boolean hasHeight = message.has("height") && message.get("height").getAsInt() > 0;
        String patternId = message.has("pattern") ? message.get("pattern").getAsString() : "bmp_spectrum";

        BitmapRendererBackend renderer = plugin.getBitmapRenderer();
        BitmapPatternManager patternMgr = plugin.getBitmapPatternManager();

        var zone = plugin.getZoneManager().getZone(zoneName);
        int[] actualDims;

        if (hasWidth && hasHeight) {
            // Explicit dimensions provided
            int width = Math.max(2, Math.min(128, message.get("width").getAsInt()));
            int height = Math.max(2, Math.min(128, message.get("height").getAsInt()));
            actualDims = renderer.initializeBitmapGrid(zone, width, height);
        } else {
            // Auto-size from zone geometry
            actualDims = renderer.initializeBitmapGrid(zone);
        }
        int actualWidth = actualDims[0];
        int actualHeight = actualDims[1];

        // Activate the pattern manager with the ACTUAL scaled dimensions
        patternMgr.activateZone(zoneName, patternId, actualWidth, actualHeight);

        // Register zone with composition manager for layers/transitions/sync
        CompositionManager comp = plugin.getCompositionManager();
        if (comp != null) {
            comp.registerZone(zoneName.toLowerCase(), actualWidth, actualHeight);
        }

        // Set zone backend to BITMAP in registry
        plugin.getRendererRegistry().setZoneBackends(zoneName,
            com.audioviz.render.RendererBackendType.BITMAP,
            com.audioviz.render.RendererBackendType.DISPLAY_ENTITIES);

        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_initialized");
        response.addProperty("zone", zoneName);
        response.addProperty("width", actualWidth);
        response.addProperty("height", actualHeight);
        response.addProperty("pattern", patternMgr.getActivePatternId(zoneName));
        response.addProperty("pixel_count", actualWidth * actualHeight);
        response.addProperty("active", true);

        // Auto-init ambient lights for this zone
        AmbientLightManager ambientMgr = plugin.getAmbientLightManager();
        if (ambientMgr != null && !ambientMgr.hasZone(zoneName)) {
            var ambientZone = plugin.getZoneManager().getZone(zoneName);
            if (ambientZone != null) {
                ambientMgr.initializeZone(ambientZone);
            }
        }

        return response;
    }

    /**
     * Teardown a bitmap (LED wall) display in a zone.
     * Deactivates the bitmap pattern manager and despawns all TextDisplay entities.
     * Expected format:
     * {
     *   "type": "teardown_bitmap",
     *   "zone": "zone_name"
     * }
     */
    private JsonObject handleTeardownBitmap(JsonObject message) {
        if (!message.has("zone")) {
            return createError("Missing required field: zone");
        }
        String zoneName = message.get("zone").getAsString();
        if (!isValidZoneName(zoneName)) {
            return createError("Invalid zone name");
        }

        // Deactivate pattern manager for this zone
        plugin.getBitmapPatternManager().deactivateZone(zoneName);

        // Teardown bitmap renderer (despawns TextDisplay entities)
        plugin.getBitmapRenderer().teardown(zoneName);

        // Revert zone backend to DISPLAY_ENTITIES
        plugin.getRendererRegistry().setZoneBackends(zoneName,
            com.audioviz.render.RendererBackendType.DISPLAY_ENTITIES,
            com.audioviz.render.RendererBackendType.DISPLAY_ENTITIES);

        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_teardown");
        response.addProperty("zone", zoneName);
        return response;
    }

    /**
     * Push a raw frame buffer to a bitmap zone.
     * Expected format:
     * {
     *   "type": "bitmap_frame",
     *   "zone": "zone_name",
     *   "pixels": "base64-encoded ARGB int array (little-endian)"
     * }
     *
     * Alternative format with JSON array (slower but easier to debug):
     * {
     *   "type": "bitmap_frame",
     *   "zone": "zone_name",
     *   "pixel_array": [0xFF0000FF, 0xFF00FF00, ...]
     * }
     */
    private JsonObject handleBitmapFrame(JsonObject message) {
        if (!message.has("zone")) {
            return createError("Missing required field: zone");
        }
        String zoneName = message.get("zone").getAsString();
        if (!isValidZoneName(zoneName)) {
            return createError("Invalid zone name");
        }

        BitmapRendererBackend renderer = plugin.getBitmapRenderer();
        if (!renderer.isBitmapZone(zoneName)) {
            return createError("Zone '" + zoneName + "' is not in bitmap mode. Call init_bitmap first.");
        }

        var config = renderer.getGridConfig(zoneName);
        int pixelCount = config.pixelCount();

        int[] pixels;

        if (message.has("pixels")) {
            // Base64-encoded binary format (fast path)
            try {
                String base64 = message.get("pixels").getAsString();
                byte[] bytes = Base64.getDecoder().decode(base64);

                if (bytes.length != pixelCount * 4) {
                    return createError("Pixel data size mismatch: expected " + (pixelCount * 4) +
                        " bytes, got " + bytes.length);
                }

                pixels = new int[pixelCount];
                ByteBuffer buf = ByteBuffer.wrap(bytes)
                    .order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < pixelCount; i++) {
                    pixels[i] = buf.getInt();
                }
            } catch (IllegalArgumentException e) {
                return createError("Invalid base64 pixel data: " + e.getMessage());
            }
        } else if (message.has("pixel_array")) {
            // JSON array format (debug-friendly)
            JsonArray arr = message.getAsJsonArray("pixel_array");
            pixels = new int[Math.min(arr.size(), pixelCount)];
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = arr.get(i).getAsInt();
            }
        } else {
            return createError("Missing pixel data: provide 'pixels' (base64) or 'pixel_array' (JSON)");
        }

        // Parse optional per-pixel brightness
        int[] brightnessArray = null;
        if (message.has("brightness")) {
            try {
                String b64Brightness = message.get("brightness").getAsString();
                byte[] brightnessBytes = java.util.Base64.getDecoder().decode(b64Brightness);
                if (brightnessBytes.length == pixelCount) {
                    brightnessArray = new int[pixelCount];
                    for (int i = 0; i < pixelCount; i++) {
                        brightnessArray[i] = Math.max(0, Math.min(15, brightnessBytes[i] & 0xFF));
                    }
                }
            } catch (Exception e) {
                // Ignore malformed brightness, continue with null
            }
        }

        renderer.applyRawFrame(zoneName, pixels, brightnessArray);

        // Tick ambient lights based on frame luminance
        AmbientLightManager ambientMgr = plugin.getAmbientLightManager();
        if (ambientMgr != null && ambientMgr.hasZone(zoneName)) {
            float intensity = averagePixelLuminance(pixels);
            ambientMgr.tick(zoneName, intensity, false);
        }

        // Silent OK for high-frequency message
        JsonObject response = new JsonObject();
        response.addProperty("type", "ok");
        return response;
    }

    /**
     * Switch the active bitmap pattern for a zone.
     * Expected format:
     * {
     *   "type": "set_bitmap_pattern",
     *   "zone": "zone_name",
     *   "pattern": "bmp_spectrogram"
     * }
     */
    private JsonObject handleSetBitmapPattern(JsonObject message) {
        if (!message.has("zone") || !message.has("pattern")) {
            return createError("Missing required fields: zone, pattern");
        }
        String zoneName = message.get("zone").getAsString();
        if (!isValidZoneName(zoneName)) {
            return createError("Invalid zone name");
        }
        String patternId = message.get("pattern").getAsString();

        BitmapPatternManager mgr = plugin.getBitmapPatternManager();
        if (!mgr.isActive(zoneName)) {
            return createError("Zone '" + zoneName + "' has no active bitmap. Call init_bitmap first.");
        }
        if (mgr.getPattern(patternId) == null) {
            return createError("Unknown bitmap pattern: " + patternId +
                ". Available: " + String.join(", ", mgr.getPatternIds()));
        }

        mgr.setPattern(zoneName, patternId);

        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_pattern_set");
        response.addProperty("zone", zoneName);
        response.addProperty("pattern", patternId);
        return response;
    }

    /**
     * List all available bitmap patterns.
     */
    private JsonObject handleGetBitmapPatterns() {
        BitmapPatternManager mgr = plugin.getBitmapPatternManager();

        JsonArray patterns = new JsonArray();
        for (String id : mgr.getPatternIds()) {
            var pattern = mgr.getPattern(id);
            JsonObject p = new JsonObject();
            p.addProperty("id", pattern.getId());
            p.addProperty("name", pattern.getName());
            p.addProperty("description", pattern.getDescription());
            patterns.add(p);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_patterns");
        response.add("patterns", patterns);
        return response;
    }

    /**
     * Get bitmap status for a zone.
     */
    private JsonObject handleGetBitmapStatus(JsonObject message) {
        if (!message.has("zone")) {
            return createError("Missing required field: zone");
        }
        String zoneName = message.get("zone").getAsString();

        BitmapRendererBackend renderer = plugin.getBitmapRenderer();
        BitmapPatternManager mgr = plugin.getBitmapPatternManager();

        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_status");
        response.addProperty("zone", zoneName);
        response.addProperty("active", renderer.isBitmapZone(zoneName));

        if (renderer.isBitmapZone(zoneName)) {
            var config = renderer.getGridConfig(zoneName);
            response.addProperty("width", config.width());
            response.addProperty("height", config.height());
            response.addProperty("pixel_count", config.pixelCount());
            response.addProperty("interpolation_ticks", config.interpolationTicks());
            response.addProperty("pattern", mgr.getActivePatternId(zoneName));
        }

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

    // ========== Zone Parity Check ==========

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

    // ========== Bitmap Transition Handlers ==========

    private JsonObject handleBitmapTransition(JsonObject message) {
        String zone = message.get("zone").getAsString().toLowerCase();
        String patternId = message.get("pattern").getAsString();
        String transitionId = message.has("transition") ? message.get("transition").getAsString() : "crossfade";
        int durationTicks = message.has("duration_ticks") ? message.get("duration_ticks").getAsInt() : 20;

        BitmapPatternManager patternMgr = plugin.getBitmapPatternManager();
        if (patternMgr == null || !patternMgr.isActive(zone)) {
            return createError("Bitmap zone not active: " + zone);
        }

        BitmapPattern newPattern = patternMgr.getPattern(patternId);
        if (newPattern == null) {
            return createError("Unknown pattern: " + patternId);
        }

        // Route through BitmapPatternManager which owns the render loop
        patternMgr.setPattern(zone, patternId, transitionId, durationTicks);

        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_transition_started");
        response.addProperty("zone", zone);
        response.addProperty("pattern", patternId);
        response.addProperty("transition", transitionId);
        response.addProperty("duration_ticks", durationTicks);
        return response;
    }

    private JsonObject handleGetBitmapTransitions() {
        TransitionManager tm = plugin.getBitmapPatternManager().getTransitionManager();
        JsonArray transitions = new JsonArray();
        for (String id : tm.getTransitionIds()) {
            JsonObject t = new JsonObject();
            t.addProperty("id", id);
            t.addProperty("name", tm.getTransition(id).getName());
            transitions.add(t);
        }
        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_transitions");
        response.add("transitions", transitions);
        return response;
    }

    // ========== Bitmap Text Handlers ==========

    private JsonObject handleBitmapMarquee(JsonObject message) {
        String zone = message.get("zone").getAsString().toLowerCase();
        String text = message.get("text").getAsString();
        int color = message.has("color") ? message.get("color").getAsInt() : 0xFFFFFFFF;

        BitmapPatternManager patternMgr = plugin.getBitmapPatternManager();
        if (patternMgr == null) return createError("Bitmap not initialized");

        // Find or activate marquee pattern
        BitmapPattern pattern = patternMgr.getPattern("bmp_marquee");
        if (pattern instanceof MarqueePattern marquee) {
            marquee.queueMessage(text, color);

            // If zone is not running marquee, switch to it
            if (patternMgr.isActive(zone) && !"bmp_marquee".equals(patternMgr.getActivePatternId(zone))) {
                patternMgr.setPattern(zone, "bmp_marquee");
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "ok");
        return response;
    }

    private JsonObject handleBitmapTrackDisplay(JsonObject message) {
        String zone = message.get("zone").getAsString().toLowerCase();
        String artist = message.has("artist") ? message.get("artist").getAsString() : "";
        String title = message.has("title") ? message.get("title").getAsString() : "";

        BitmapPatternManager patternMgr = plugin.getBitmapPatternManager();
        if (patternMgr == null) return createError("Bitmap not initialized");

        BitmapPattern pattern = patternMgr.getPattern("bmp_track_display");
        if (pattern instanceof TrackDisplayPattern trackDisplay) {
            if (message.has("artist_color")) trackDisplay.setArtistColor(message.get("artist_color").getAsInt());
            if (message.has("title_color")) trackDisplay.setTitleColor(message.get("title_color").getAsInt());
            trackDisplay.setTrack(artist, title);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "ok");
        return response;
    }

    private JsonObject handleBitmapCountdown(JsonObject message) {
        String zone = message.get("zone").getAsString().toLowerCase();
        String action = message.has("action") ? message.get("action").getAsString() : "start";

        BitmapPatternManager patternMgr = plugin.getBitmapPatternManager();
        if (patternMgr == null) return createError("Bitmap not initialized");

        BitmapPattern pattern = patternMgr.getPattern("bmp_countdown");
        if (pattern instanceof CountdownPattern countdown) {
            switch (action) {
                case "start" -> {
                    int seconds = message.has("seconds") ? message.get("seconds").getAsInt() : 10;
                    countdown.start(seconds);
                    if (patternMgr.isActive(zone)) {
                        patternMgr.setPattern(zone, "bmp_countdown");
                    }
                }
                case "stop" -> countdown.stop();
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "ok");
        return response;
    }

    private JsonObject handleBitmapChat(JsonObject message) {
        String zone = message.get("zone").getAsString().toLowerCase();
        String playerName = message.has("player") ? message.get("player").getAsString() : "VJ";
        // Accept both "text" and "message" field names for flexibility
        String text = message.has("message") ? message.get("message").getAsString()
                    : message.has("text") ? message.get("text").getAsString() : "";

        BitmapPatternManager patternMgr = plugin.getBitmapPatternManager();
        if (patternMgr == null) return createError("Bitmap not initialized");

        BitmapPattern pattern = patternMgr.getPattern("bmp_chat_wall");
        if (pattern instanceof ChatWallPattern chatWall) {
            chatWall.addMessage(playerName, text);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "ok");
        return response;
    }

    // ========== Bitmap Effects Handlers ==========

    private JsonObject handleBitmapEffects(JsonObject message) {
        String action = message.get("action").getAsString();

        EffectsProcessor effects = plugin.getGlobalBitmapEffects();
        if (effects == null) return createError("Bitmap effects not initialized");

        switch (action) {
            case "strobe" -> {
                effects.setStrobeEnabled(message.has("enabled") ? message.get("enabled").getAsBoolean() : true);
                if (message.has("divisor")) effects.setStrobeDivisor(message.get("divisor").getAsInt());
                if (message.has("color")) effects.setStrobeColor(message.get("color").getAsInt());
            }
            case "freeze" -> {
                boolean freeze = message.has("enabled") ? message.get("enabled").getAsBoolean() : true;
                if (freeze) {
                    // Freeze current frame of specified zone
                    String zone = message.has("zone") ? message.get("zone").getAsString().toLowerCase() : "";
                    BitmapFrameBuffer buf = plugin.getBitmapPatternManager().getFrameBuffer(zone);
                    if (buf != null) effects.freeze(buf);
                } else {
                    effects.unfreeze();
                }
            }
            case "brightness" -> effects.setBrightness(message.get("level").getAsDouble());
            case "blackout" -> effects.blackout(message.has("enabled") ? message.get("enabled").getAsBoolean() : true);
            case "wash" -> {
                int color = message.get("color").getAsInt();
                double opacity = message.has("opacity") ? message.get("opacity").getAsDouble() : 0.3;
                effects.setWash(color, opacity);
            }
            case "clear_wash" -> effects.clearWash();
            case "beat_flash" -> effects.setBeatFlashEnabled(
                message.has("enabled") ? message.get("enabled").getAsBoolean() : true);
            case "reset" -> effects.reset();
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "ok");
        return response;
    }

    private JsonObject handleBitmapPalette(JsonObject message) {
        String paletteId = message.get("palette").getAsString();

        EffectsProcessor effects = plugin.getGlobalBitmapEffects();
        if (effects == null) return createError("Bitmap effects not initialized");

        if ("none".equals(paletteId) || "clear".equals(paletteId)) {
            effects.clearPalette();
        } else {
            ColorPalette palette = null;
            for (ColorPalette p : ColorPalette.BUILT_IN) {
                if (p.getId().equals(paletteId)) {
                    palette = p;
                    break;
                }
            }
            if (palette == null) return createError("Unknown palette: " + paletteId);
            effects.setPalette(palette);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_palette_set");
        response.addProperty("palette", paletteId);
        return response;
    }

    private JsonObject handleGetBitmapPalettes() {
        JsonArray palettes = new JsonArray();
        for (ColorPalette p : ColorPalette.BUILT_IN) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", p.getId());
            obj.addProperty("name", p.getName());
            palettes.add(obj);
        }
        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_palettes");
        response.add("palettes", palettes);
        return response;
    }

    // ========== Bitmap Layer Handler ==========

    private JsonObject handleBitmapLayer(JsonObject message) {
        String zone = message.get("zone").getAsString().toLowerCase();
        String action = message.get("action").getAsString();

        CompositionManager comp = plugin.getCompositionManager();
        if (comp == null) return createError("Composition manager not initialized");

        CompositionManager.ZoneState zoneState = comp.getZone(zone);
        if (zoneState == null) return createError("Zone not registered with composition manager: " + zone);

        switch (action) {
            case "set" -> {
                String patternId = message.get("pattern").getAsString();
                String blendMode = message.has("blend_mode") ? message.get("blend_mode").getAsString() : "ADDITIVE";
                double opacity = message.has("opacity") ? message.get("opacity").getAsDouble() : 0.5;

                BitmapPattern pattern = plugin.getBitmapPatternManager().getPattern(patternId);
                if (pattern == null) return createError("Unknown pattern: " + patternId);

                LayerCompositor.BlendMode mode;
                try {
                    mode = LayerCompositor.BlendMode.valueOf(blendMode.toUpperCase());
                } catch (IllegalArgumentException e) {
                    mode = LayerCompositor.BlendMode.ADDITIVE;
                }

                zoneState.setSecondaryLayer(pattern, mode, opacity);
            }
            case "clear" -> zoneState.clearSecondaryLayer();
            case "opacity" -> zoneState.secondaryOpacity = message.get("opacity").getAsDouble();
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "ok");
        return response;
    }

    // ========== Bitmap Game Integration Handlers ==========

    private JsonObject handleBitmapFirework(JsonObject message) {
        float x = message.has("x") ? message.get("x").getAsFloat() : 0.5f;
        float y = message.has("y") ? message.get("y").getAsFloat() : 0.3f;

        BitmapPatternManager patternMgr = plugin.getBitmapPatternManager();
        if (patternMgr == null) return createError("Bitmap not initialized");

        BitmapPattern pattern = patternMgr.getPattern("bmp_fireworks");
        if (pattern instanceof FireworkPattern fireworks) {
            fireworks.spawn(x, y);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "ok");
        return response;
    }

    // ========== Bitmap Image Handler ==========

    private JsonObject handleBitmapImage(JsonObject message) {
        String zone = message.get("zone").getAsString().toLowerCase();
        String action = message.has("action") ? message.get("action").getAsString() : "load";

        BitmapPatternManager patternMgr = plugin.getBitmapPatternManager();
        if (patternMgr == null) return createError("Bitmap not initialized");

        BitmapPattern pattern = patternMgr.getPattern("bmp_image");
        if (!(pattern instanceof ImagePattern imagePattern)) {
            return createError("Image pattern not registered");
        }

        switch (action) {
            case "load_pixels" -> {
                // Load from base64-encoded ARGB pixel array
                if (message.has("pixels")) {
                    String b64 = message.get("pixels").getAsString();
                    byte[] bytes = java.util.Base64.getDecoder().decode(b64);
                    java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    int[] pixels = new int[bytes.length / 4];
                    bb.asIntBuffer().get(pixels);

                    int w = message.get("width").getAsInt();
                    int h = message.get("height").getAsInt();
                    imagePattern.loadFromPixels(pixels, w, h);

                    if (patternMgr.isActive(zone)) {
                        patternMgr.setPattern(zone, "bmp_image");
                    }
                }
            }
            case "load_file" -> {
                String path = message.get("path").getAsString();
                BitmapFrameBuffer buf = patternMgr.getFrameBuffer(zone);
                if (buf != null) {
                    imagePattern.loadFromFile(new java.io.File(path), buf.getWidth(), buf.getHeight());
                    if (patternMgr.isActive(zone)) {
                        patternMgr.setPattern(zone, "bmp_image");
                    }
                }
            }
            case "set_mode" -> {
                String modeName = message.get("mode").getAsString().toUpperCase();
                try {
                    imagePattern.setMode(ImagePattern.ModulationMode.valueOf(modeName));
                } catch (IllegalArgumentException ignored) {}
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "ok");
        return response;
    }

    // ========== DJ Logo Handler ==========

    private JsonObject handleBitmapDjLogo(JsonObject message) {
        String zone = message.get("zone").getAsString().toLowerCase();
        String action = message.has("action") ? message.get("action").getAsString() : "load_file";

        BitmapPatternManager patternMgr = plugin.getBitmapPatternManager();
        if (patternMgr == null) return createError("Bitmap not initialized");

        BitmapPattern pattern = patternMgr.getPattern("bmp_dj_logo");
        if (!(pattern instanceof DJLogoPattern logoPattern)) {
            return createError("DJ Logo pattern not registered");
        }

        switch (action) {
            case "load_file" -> {
                String path = message.get("path").getAsString();
                java.io.File file = new java.io.File(path);
                // Resolve relative paths against plugin data folder
                if (!file.isAbsolute()) {
                    file = new java.io.File(plugin.getDataFolder(), path);
                }
                BitmapFrameBuffer buf = patternMgr.getFrameBuffer(zone);
                if (buf != null) {
                    boolean ok = logoPattern.loadFromFile(file, buf.getWidth(), buf.getHeight());
                    if (ok && patternMgr.isActive(zone)) {
                        patternMgr.setPattern(zone, "bmp_dj_logo");
                    }
                    if (!ok) return createError("Failed to load image: " + path);
                } else {
                    return createError("Zone not active: " + zone);
                }
            }
            case "load_pixels" -> {
                if (message.has("pixels")) {
                    String b64 = message.get("pixels").getAsString();
                    byte[] bytes = Base64.getDecoder().decode(b64);
                    ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                    int[] pixels = new int[bytes.length / 4];
                    bb.asIntBuffer().get(pixels);

                    int w = message.get("width").getAsInt();
                    int h = message.get("height").getAsInt();
                    logoPattern.loadFromPixels(pixels, w, h);

                    if (patternMgr.isActive(zone)) {
                        patternMgr.setPattern(zone, "bmp_dj_logo");
                    }
                }
            }
            case "set_mode" -> {
                String modeName = message.get("mode").getAsString().toUpperCase();
                try {
                    logoPattern.setMode(DJLogoPattern.LogoMode.valueOf(modeName));
                } catch (IllegalArgumentException ignored) {
                    return createError("Unknown mode: " + modeName);
                }
            }
            case "set_threshold" -> {
                int threshold = message.get("threshold").getAsInt();
                logoPattern.setThreshold(threshold);
            }
            case "set_palette" -> {
                String paletteId = message.get("palette").getAsString();
                ColorPalette found = null;
                for (ColorPalette p : ColorPalette.BUILT_IN) {
                    if (p.getId().equals(paletteId)) { found = p; break; }
                }
                if (found != null) {
                    logoPattern.setPalette(found);
                } else {
                    return createError("Unknown palette: " + paletteId);
                }
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "ok");
        return response;
    }

    // ========== Bitmap Composition Handler ==========

    private JsonObject handleBitmapComposition(JsonObject message) {
        String action = message.get("action").getAsString();

        CompositionManager comp = plugin.getCompositionManager();
        if (comp == null) return createError("Composition manager not initialized");

        switch (action) {
            case "set_sync_mode" -> {
                String mode = message.get("mode").getAsString().toUpperCase();
                try {
                    comp.setSyncMode(CompositionManager.SyncMode.valueOf(mode));
                } catch (IllegalArgumentException ignored) {}
                if (message.has("mirror_source")) {
                    comp.setMirrorSource(message.get("mirror_source").getAsString().toLowerCase());
                }
            }
            case "set_shared_palette" -> {
                String paletteId = message.get("palette").getAsString();
                ColorPalette palette = null;
                for (ColorPalette p : ColorPalette.BUILT_IN) {
                    if (p.getId().equals(paletteId)) { palette = p; break; }
                }
                if (palette != null) comp.setSharedPalette(palette);
                else comp.clearSharedPalette();
            }
            case "flash_all" -> {
                int color = message.has("color") ? message.get("color").getAsInt() : 0xFFFFFFFF;
                double intensity = message.has("intensity") ? message.get("intensity").getAsDouble() : 0.5;
                comp.flashAll(color, intensity);
            }
            case "get_zones" -> {
                JsonArray zones = new JsonArray();
                for (String z : comp.getZoneNames()) zones.add(z);
                JsonObject response = new JsonObject();
                response.addProperty("type", "bitmap_composition_zones");
                response.add("zones", zones);
                response.addProperty("sync_mode", comp.getSyncMode().name());
                return response;
            }
        }

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

    private float averagePixelLuminance(int[] argbPixels) {
        if (argbPixels == null || argbPixels.length == 0) return 0f;
        long sum = 0;
        for (int argb : argbPixels) {
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            sum += (int)(0.2126 * r + 0.7152 * g + 0.0722 * b);
        }
        return (float)(sum / argbPixels.length) / 255.0f;
    }
}
