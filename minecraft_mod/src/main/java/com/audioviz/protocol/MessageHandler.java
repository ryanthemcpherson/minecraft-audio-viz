package com.audioviz.protocol;

import com.audioviz.AudioVizMod;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.bitmap.BitmapPatternManager;
import com.audioviz.bitmap.effects.ColorPalette;
import com.audioviz.bitmap.transitions.BitmapTransition;
import com.audioviz.decorators.BannerConfig;
import com.audioviz.decorators.DJInfo;
import com.audioviz.patterns.AudioState;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageManager;
import com.audioviz.stages.StageTemplate;
import com.audioviz.stages.StageZoneConfig;
import com.audioviz.stages.StageZoneRole;
import com.audioviz.voice.VoicechatIntegration;
import com.audioviz.zones.VisualizationZone;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Handles WebSocket protocol messages and dispatches to appropriate handlers.
 * Ported from Paper plugin — all core handlers are fully wired to Fabric subsystems.
 */
public class MessageHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz");
    private final AudioVizMod mod;

    /** Valid zone/stage name: 1-64 alphanumeric, underscore, or hyphen. */
    private static final Pattern VALID_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");
    private static final int MAX_NAME_LENGTH = 64;

    // Latest audio state from DJ client
    private volatile AudioState latestAudioState;

    // Callbacks for subsystems (set when each subsystem is initialized)
    private BatchUpdateHandler batchUpdateHandler;
    private BitmapFrameHandler bitmapFrameHandler;

    public MessageHandler(AudioVizMod mod) {
        this.mod = mod;
    }

    private static boolean isValidZoneName(String name) {
        return name != null
            && !name.isEmpty()
            && name.length() <= MAX_NAME_LENGTH
            && VALID_NAME_PATTERN.matcher(name).matches();
    }

    /**
     * Handle an incoming message and return a response (or null).
     */
    public JsonObject handleMessage(String type, JsonObject message) {
        return switch (type) {
            case "ping" -> handlePing();
            case "get_zones" -> handleGetZones();
            case "get_zone" -> handleGetZone(message);
            case "init_pool" -> handleInitPool(message);
            case "batch_update" -> handleBatchUpdate(message);
            case "set_visible" -> handleSetVisible(message);
            case "cleanup_zone" -> handleCleanupZone(message);
            case "set_zone_config" -> handleSetZoneConfig(message);
            case "set_renderer_backend" -> handleSetRendererBackend(message);
            case "renderer_capabilities", "get_renderer_capabilities" -> handleRendererCapabilities(message);
            case "audio_state" -> handleAudioState(message);
            // Bitmap rendering
            case "init_bitmap" -> handleInitBitmap(message);
            case "teardown_bitmap" -> handleTeardownBitmap(message);
            case "bitmap_frame" -> handleBitmapFrame(message);
            case "set_bitmap_pattern" -> handleSetBitmapPattern(message);
            case "get_bitmap_patterns" -> handleGetBitmapPatterns();
            case "get_bitmap_transitions" -> handleGetBitmapTransitions();
            case "get_bitmap_palettes" -> handleGetBitmapPalettes();
            // Stage management
            case "get_stages" -> handleGetStages();
            case "create_stage" -> handleCreateStage(message);
            case "delete_stage" -> handleDeleteStage(message);
            case "activate_stage" -> handleActivateStage(message);
            case "deactivate_stage" -> handleDeactivateStage(message);
            case "get_stage" -> handleGetStage(message);
            case "update_stage" -> handleUpdateStage(message);
            case "set_stage_zone_config" -> handleSetStageZoneConfig(message);
            case "get_stage_templates" -> handleGetStageTemplates();
            case "scan_stage_blocks" -> handleScanStageBlocks(message);
            // DJ info
            case "dj_info" -> handleDjInfo(message);
            case "banner_config" -> handleBannerConfig(message);
            // Voice chat
            case "voice_audio" -> handleVoiceAudio(message);
            case "voice_config" -> handleVoiceConfig(message);
            case "get_voice_status" -> handleGetVoiceStatus();
            default -> createError("Unknown message type: " + type);
        };
    }

    // --- Pure-logic handlers (no Paper deps) ---

    private JsonObject handlePing() {
        JsonObject response = new JsonObject();
        response.addProperty("type", "pong");
        response.addProperty("timestamp", System.currentTimeMillis());
        return response;
    }

    private JsonObject handleAudioState(JsonObject message) {
        // Parse audio state from DJ client — pure data, no API deps
        double[] bands = new double[5];
        if (message.has("bands")) {
            JsonArray bandsJson = message.getAsJsonArray("bands");
            for (int i = 0; i < Math.min(bandsJson.size(), 5); i++) {
                bands[i] = clamp(bandsJson.get(i).getAsDouble(), 0.0, 1.0);
            }
        }
        double amplitude = clamp(
            message.has("amplitude") ? message.get("amplitude").getAsDouble() : 0.0,
            0.0, 1.0);
        boolean isBeat = message.has("is_beat") && message.get("is_beat").getAsBoolean();
        double beatIntensity = clamp(
            message.has("beat_intensity") ? message.get("beat_intensity").getAsDouble() : 0.0,
            0.0, 1.0);
        double tempoConfidence = clamp(
            message.has("tempo_confidence") ? message.get("tempo_confidence").getAsDouble() : 0.0,
            0.0, 1.0);
        double beatPhase = clamp(
            message.has("beat_phase") ? message.get("beat_phase").getAsDouble() : 0.0,
            0.0, 1.0);
        long frame = message.has("frame") ? message.get("frame").getAsLong() : 0;

        latestAudioState = new AudioState(bands, amplitude, isBeat, beatIntensity,
            tempoConfidence, beatPhase, frame);
        return null; // No response needed
    }

    // --- Subsystem handlers ---

    private JsonObject handleGetZones() {
        JsonObject response = new JsonObject();
        response.addProperty("type", "zones");
        JsonArray zonesArr = new JsonArray();
        for (VisualizationZone zone : mod.getZoneManager().getAllZones()) {
            zonesArr.add(serializeZone(zone));
        }
        response.add("zones", zonesArr);
        return response;
    }

    private static JsonObject serializeZone(VisualizationZone zone) {
        JsonObject z = new JsonObject();
        z.addProperty("name", zone.getName());
        z.addProperty("id", zone.getId().toString());

        JsonObject origin = new JsonObject();
        origin.addProperty("x", zone.getOrigin().getX());
        origin.addProperty("y", zone.getOrigin().getY());
        origin.addProperty("z", zone.getOrigin().getZ());
        z.add("origin", origin);

        JsonObject size = new JsonObject();
        size.addProperty("x", zone.getSize().x);
        size.addProperty("y", zone.getSize().y);
        size.addProperty("z", zone.getSize().z);
        z.add("size", size);

        z.addProperty("rotation", zone.getRotation());
        return z;
    }

    private JsonObject handleGetZone(JsonObject message) {
        String name = message.has("zone") ? message.get("zone").getAsString() : null;
        if (name == null || !isValidZoneName(name)) return createError("Invalid zone name");
        VisualizationZone zone = mod.getZoneManager().getZone(name);
        if (zone == null) return createError("Zone not found: " + name);
        JsonObject response = serializeZone(zone);
        response.addProperty("type", "zone");
        return response;
    }

    private JsonObject handleInitPool(JsonObject message) {
        String zone = message.has("zone") ? message.get("zone").getAsString() : null;
        if (zone == null || !isValidZoneName(zone)) return createError("Invalid zone name");
        int entityCount = message.has("entity_count") ? message.get("entity_count").getAsInt()
                       : message.has("count") ? message.get("count").getAsInt() : 64;
        if (entityCount < 1 || entityCount > 10000) return createError("Invalid entity_count (1-10000)");

        var vizZone = mod.getZoneManager().getZone(zone);
        if (vizZone == null) return createError("Zone not found: " + zone);
        if (vizZone.getWorld() == null) return createError("Zone world not loaded: " + zone);

        String material = message.has("material") ? message.get("material").getAsString() : null;
        net.minecraft.block.BlockState blockState = material != null
            ? com.audioviz.render.MaterialResolver.resolve(material) : null;

        mod.getVirtualRenderer().initializePool(zone, vizZone, entityCount, vizZone.getWorld(), blockState);
        LOGGER.info("Initialized entity pool '{}' with {} entities (material={})", zone, entityCount,
            material != null ? material : "default");

        JsonObject response = new JsonObject();
        response.addProperty("type", "pool_initialized");
        response.addProperty("zone", zone);
        response.addProperty("entity_count", entityCount);
        return response;
    }

    private JsonObject handleBatchUpdate(JsonObject message) {
        // Extract embedded audio state from batch_update (VJ server sends it here, not as separate message)
        if (message.has("bands")) {
            handleAudioState(message);
        }

        if (batchUpdateHandler != null) {
            return batchUpdateHandler.handle(message);
        }
        return null; // Silently drop if renderer not ready
    }

    private JsonObject handleSetVisible(JsonObject message) {
        String zone = message.has("zone") ? message.get("zone").getAsString() : null;
        if (zone == null || !isValidZoneName(zone)) return null;
        boolean visible = !message.has("visible") || message.get("visible").getAsBoolean();

        // Hide all entities by scaling to zero (preserves pool for fast show/hide)
        if (!visible && mod.getVirtualRenderer().hasPool(zone)) {
            mod.getVirtualRenderer().hideAll(zone);
            LOGGER.debug("Hidden virtual entity pool for zone '{}'", zone);
        }
        return null;
    }

    private JsonObject handleCleanupZone(JsonObject message) {
        String zone = message.has("zone") ? message.get("zone").getAsString() : null;
        if (zone == null) return createError("Missing zone");

        try {
            mod.getMapRenderer().destroyDisplay(zone);
            if (mod.getBitmapToEntityBridge() != null) mod.getBitmapToEntityBridge().destroyWall(zone);
            mod.getVirtualRenderer().destroyPool(zone);
            BitmapPatternManager bpm = mod.getBitmapPatternManager();
            if (bpm != null) bpm.deactivateZone(zone);
            if (mod.getAmbientLightManager() != null) mod.getAmbientLightManager().teardownZone(zone);
            if (mod.getBeatEventManager() != null) mod.getBeatEventManager().removeZoneConfig(zone);

            LOGGER.info("Cleaned up zone '{}'", zone);
            JsonObject response = new JsonObject();
            response.addProperty("type", "zone_cleaned");
            response.addProperty("zone", zone);
            return response;
        } catch (Exception e) {
            LOGGER.error("Failed to cleanup zone '{}': {}", zone, e.getMessage(), e);
            return createError("Cleanup failed: " + e.getMessage());
        }
    }

    private JsonObject handleSetZoneConfig(JsonObject message) {
        String zone = message.has("zone") ? message.get("zone").getAsString() : null;
        if (zone == null || !isValidZoneName(zone)) return createError("Invalid zone name");

        var vizZone = mod.getZoneManager().getZone(zone);
        if (vizZone == null) return createError("Zone not found: " + zone);

        if (message.has("size_x") && message.has("size_y") && message.has("size_z")) {
            vizZone.setSize(
                message.get("size_x").getAsFloat(),
                message.get("size_y").getAsFloat(),
                message.get("size_z").getAsFloat()
            );
        }
        if (message.has("rotation")) {
            vizZone.setRotation(message.get("rotation").getAsFloat());
        }
        if (message.has("glow_on_beat")) {
            vizZone.setGlowOnBeat(message.get("glow_on_beat").getAsBoolean());
        }
        if (message.has("dynamic_brightness")) {
            vizZone.setDynamicBrightness(message.get("dynamic_brightness").getAsBoolean());
        }

        mod.getZoneManager().saveZones();

        JsonObject response = new JsonObject();
        response.addProperty("type", "zone_config_updated");
        response.addProperty("zone", zone);
        return response;
    }

    private JsonObject handleSetRendererBackend(JsonObject message) {
        String zone = message.has("zone") ? message.get("zone").getAsString() : null;
        String backend = message.has("backend") ? message.get("backend").getAsString() : null;
        if (zone == null || backend == null) return createError("Missing zone or backend");

        // When switching to entity mode, ensure the pool exists so batch_update
        // messages aren't silently dropped.  The VJ server reliably sends a
        // separate init_pool when switching *from* bitmap, but going directly to
        // entity mode skips that — so we auto-initialize here as a fallback.
        if ("VIRTUAL_ENTITY".equalsIgnoreCase(backend) && !mod.getVirtualRenderer().hasPool(zone)) {
            var vizZone = mod.getZoneManager().getZone(zone);
            if (vizZone != null && vizZone.getWorld() != null) {
                int entityCount = message.has("entity_count") ? message.get("entity_count").getAsInt() : 64;
                String material = message.has("material") ? message.get("material").getAsString() : null;
                net.minecraft.block.BlockState blockState = material != null
                    ? com.audioviz.render.MaterialResolver.resolve(material) : null;
                mod.getVirtualRenderer().initializePool(zone, vizZone, entityCount, vizZone.getWorld(), blockState);
                LOGGER.info("Auto-initialized entity pool '{}' ({} entities) on backend switch", zone, entityCount);
            }
        }

        LOGGER.info("Renderer backend for zone '{}' set to '{}'", zone, backend);

        JsonObject response = new JsonObject();
        response.addProperty("type", "renderer_backend_set");
        response.addProperty("zone", zone);
        response.addProperty("backend", backend);
        return response;
    }

    private JsonObject handleRendererCapabilities(JsonObject message) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "renderer_capabilities");
        JsonArray backends = new JsonArray();
        backends.add("MAP");
        backends.add("VIRTUAL_ENTITY");
        backends.add("PARTICLE");
        response.add("backends", backends);
        response.addProperty("bundle_packets", true);
        response.addProperty("virtual_entities", true);
        response.addProperty("map_renderer", true);
        return response;
    }

    private JsonObject handleInitBitmap(JsonObject message) {
        String zone = message.has("zone") ? message.get("zone").getAsString() : null;
        if (zone == null || !isValidZoneName(zone)) return createError("Invalid zone name");
        String patternId = message.has("pattern") ? message.get("pattern").getAsString() : "bmp_spectrum";
        // Backend: "map" (default, high-res map tiles) or "entity" (block display LED wall)
        String backend = message.has("backend") ? message.get("backend").getAsString() : "map";

        int rawW = message.has("width") ? message.get("width").getAsInt() : 128;
        int rawH = message.has("height") ? message.get("height").getAsInt() : 128;

        int width, height;
        if ("entity".equals(backend)) {
            width = Math.max(1, rawW);
            height = Math.max(1, rawH);
        } else {
            width = Math.max(128, ((rawW + 127) / 128) * 128);
            height = Math.max(128, ((rawH + 127) / 128) * 128);
        }

        try {
            BitmapPatternManager bpm = mod.getBitmapPatternManager();
            if (bpm == null) return createError("Bitmap pattern manager not initialized");

            var vizZone = mod.getZoneManager().getZone(zone);
            if (vizZone != null && vizZone.getWorld() != null) {
                Direction facing = directionFromRotation(vizZone.getRotation());
                if ("entity".equals(backend)) {
                    mod.getMapRenderer().destroyDisplay(zone);
                    bpm.activateZone(zone, patternId, width, height);
                    if (mod.getBitmapToEntityBridge() != null) {
                        mod.getBitmapToEntityBridge().initializeWall(zone, vizZone, width, height,
                            vizZone.getWorld(), facing);
                    }
                    LOGGER.info("Initialized entity wall for bitmap zone '{}' ({}x{}, facing {})",
                        zone, width, height, facing);
                } else {
                    if (mod.getBitmapToEntityBridge() != null) mod.getBitmapToEntityBridge().destroyWall(zone);
                    // Map display derives tile count from zone dimensions and returns actual pixel size
                    int[] actualSize = mod.getMapRenderer().initializeDisplay(zone, vizZone, width, height,
                        vizZone.getWorld(), facing);
                    width = actualSize[0];
                    height = actualSize[1];
                    bpm.activateZone(zone, patternId, width, height);
                    LOGGER.info("Initialized map display for bitmap zone '{}' ({}x{}, facing {})",
                        zone, width, height, facing);
                }
            } else {
                bpm.activateZone(zone, patternId, width, height);
            }

            JsonObject response = new JsonObject();
            response.addProperty("type", "bitmap_initialized");
            response.addProperty("zone", zone);
            response.addProperty("width", width);
            response.addProperty("height", height);
            response.addProperty("pattern", patternId);
            response.addProperty("backend", backend);
            return response;
        } catch (Exception e) {
            LOGGER.error("Failed to initialize bitmap zone '{}': {}", zone, e.getMessage(), e);
            return createError("Init bitmap failed: " + e.getMessage());
        }
    }

    private JsonObject handleTeardownBitmap(JsonObject message) {
        String zone = message.has("zone") ? message.get("zone").getAsString() : null;
        if (zone == null) return createError("Missing zone");
        try {
            BitmapPatternManager bpm = mod.getBitmapPatternManager();
            if (bpm != null) bpm.deactivateZone(zone);
            mod.getMapRenderer().destroyDisplay(zone);
            if (mod.getBitmapToEntityBridge() != null) mod.getBitmapToEntityBridge().destroyWall(zone);
            JsonObject response = new JsonObject();
            response.addProperty("type", "bitmap_teardown");
            response.addProperty("zone", zone);
            return response;
        } catch (Exception e) {
            LOGGER.error("Failed to teardown bitmap zone '{}': {}", zone, e.getMessage(), e);
            return createError("Teardown failed: " + e.getMessage());
        }
    }

    private JsonObject handleBitmapFrame(JsonObject message) {
        if (bitmapFrameHandler != null) {
            return bitmapFrameHandler.handle(message);
        }
        return null;
    }

    private JsonObject handleSetBitmapPattern(JsonObject message) {
        String zone = message.has("zone") ? message.get("zone").getAsString() : null;
        String patternId = message.has("pattern") ? message.get("pattern").getAsString() : null;
        if (zone == null || patternId == null) return createError("Missing zone or pattern");

        BitmapPatternManager bpm = mod.getBitmapPatternManager();
        if (bpm == null) return createError("Bitmap pattern manager not initialized");

        try {
            String transition = message.has("transition") ? message.get("transition").getAsString() : null;
            int duration = message.has("duration") ? message.get("duration").getAsInt() : 0;
            bpm.setPattern(zone, patternId, transition, duration);

            JsonObject response = new JsonObject();
            response.addProperty("type", "pattern_set_ok");
            response.addProperty("zone", zone);
            response.addProperty("pattern", patternId);
            return response;
        } catch (Exception e) {
            LOGGER.error("Failed to set bitmap pattern '{}' on zone '{}': {}", patternId, zone, e.getMessage(), e);
            return createError("Failed to set pattern: " + e.getMessage());
        }
    }

    private JsonObject handleGetBitmapPatterns() {
        BitmapPatternManager bpm = mod.getBitmapPatternManager();
        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_patterns");
        JsonArray patterns = new JsonArray();
        if (bpm != null) {
            for (BitmapPattern p : bpm.getAllPatterns()) {
                JsonObject pObj = new JsonObject();
                pObj.addProperty("id", p.getId());
                pObj.addProperty("name", p.getName());
                pObj.addProperty("description", p.getDescription());
                patterns.add(pObj);
            }
        }
        response.add("patterns", patterns);
        return response;
    }

    private JsonObject handleGetBitmapTransitions() {
        BitmapPatternManager bpm = mod.getBitmapPatternManager();
        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_transitions");
        JsonArray transitions = new JsonArray();
        if (bpm != null) {
            var tm = bpm.getTransitionManager();
            if (tm != null) {
                for (String id : tm.getTransitionIds()) {
                    BitmapTransition t = tm.getTransition(id);
                    if (t == null) continue;
                    JsonObject tObj = new JsonObject();
                    tObj.addProperty("id", t.getId());
                    tObj.addProperty("name", t.getName());
                    transitions.add(tObj);
                }
            }
        }
        response.add("transitions", transitions);
        return response;
    }

    private JsonObject handleGetBitmapPalettes() {
        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_palettes");
        JsonArray palettes = new JsonArray();
        for (ColorPalette p : ColorPalette.BUILT_IN) {
            JsonObject pObj = new JsonObject();
            pObj.addProperty("id", p.getId());
            pObj.addProperty("name", p.getName());
            palettes.add(pObj);
        }
        response.add("palettes", palettes);
        return response;
    }

    private JsonObject handleGetStages() {
        StageManager sm = mod.getStageManager();
        JsonObject response = new JsonObject();
        response.addProperty("type", "stages");
        JsonArray stagesArr = new JsonArray();
        if (sm != null) {
            for (Stage stage : sm.getAllStages()) {
                JsonObject s = new JsonObject();
                s.addProperty("name", stage.getName());
                s.addProperty("template", stage.getTemplateName());
                s.addProperty("active", stage.isActive());
                s.addProperty("zone_count", stage.getRoleToZone().size());
                s.addProperty("total_roles", StageZoneRole.values().length);
                stagesArr.add(s);
            }
        }
        response.add("stages", stagesArr);
        return response;
    }

    private JsonObject handleCreateStage(JsonObject message) {
        StageManager sm = mod.getStageManager();
        if (sm == null) return createError("Stage system not initialized");
        String name = message.has("name") ? message.get("name").getAsString() : null;
        if (name == null || !isValidZoneName(name)) return createError("Invalid stage name");
        String template = message.has("template") ? message.get("template").getAsString() : "custom";
        int x = message.has("x") ? message.get("x").getAsInt() : 0;
        int y = message.has("y") ? message.get("y").getAsInt() : 64;
        int z = message.has("z") ? message.get("z").getAsInt() : 0;
        String world = message.has("world") ? message.get("world").getAsString() : "minecraft:overworld";
        Stage stage = sm.createStage(name, new BlockPos(x, y, z), world, template);
        if (stage == null) return createError("Stage already exists: " + name);
        JsonObject response = new JsonObject();
        response.addProperty("type", "stage_created");
        response.addProperty("name", stage.getName());
        return response;
    }

    private JsonObject handleDeleteStage(JsonObject message) {
        StageManager sm = mod.getStageManager();
        if (sm == null) return createError("Stage system not initialized");
        String name = message.has("name") ? message.get("name").getAsString() : null;
        if (name == null) return createError("Missing stage name");
        boolean deleted = sm.deleteStage(name);
        if (!deleted) return createError("Stage not found: " + name);
        JsonObject response = new JsonObject();
        response.addProperty("type", "stage_deleted");
        response.addProperty("name", name);
        return response;
    }

    private JsonObject handleActivateStage(JsonObject message) {
        StageManager sm = mod.getStageManager();
        if (sm == null) return createError("Stage system not initialized");
        String name = message.has("name") ? message.get("name").getAsString() : null;
        if (name == null) return createError("Missing stage name");
        Stage stage = sm.getStage(name);
        if (stage == null) return createError("Stage not found: " + name);
        sm.activateStage(stage);
        JsonObject response = new JsonObject();
        response.addProperty("type", "stage_activated");
        response.addProperty("name", name);
        return response;
    }

    private JsonObject handleDeactivateStage(JsonObject message) {
        StageManager sm = mod.getStageManager();
        if (sm == null) return createError("Stage system not initialized");
        String name = message.has("name") ? message.get("name").getAsString() : null;
        if (name == null) return createError("Missing stage name");
        Stage stage = sm.getStage(name);
        if (stage == null) return createError("Stage not found: " + name);
        sm.deactivateStage(stage);
        JsonObject response = new JsonObject();
        response.addProperty("type", "stage_deactivated");
        response.addProperty("name", name);
        return response;
    }

    private JsonObject handleDjInfo(JsonObject message) {
        var sdm = mod.getStageDecoratorManager();
        if (sdm == null) return null;
        String djName = message.has("dj_name") ? message.get("dj_name").getAsString() : "";
        String djId = message.has("dj_id") ? message.get("dj_id").getAsString() : "";
        double bpm = message.has("bpm") ? message.get("bpm").getAsDouble() : 0.0;
        boolean active = message.has("active") && message.get("active").getAsBoolean();
        sdm.updateDJInfo(new DJInfo(djName, djId, bpm, active, System.currentTimeMillis()));
        return null;
    }

    private JsonObject handleBannerConfig(JsonObject message) {
        var sdm = mod.getStageDecoratorManager();
        if (sdm == null) return null;
        sdm.setCurrentBannerConfig(BannerConfig.fromJson(message));
        LOGGER.debug("Updated banner config: {}", sdm.getCurrentBannerConfig());
        return null;
    }

    private JsonObject handleVoiceAudio(JsonObject message) {
        VoicechatIntegration vc = mod.getVoicechatIntegration();
        if (vc == null || !vc.isAvailable()) return null;
        String format = message.has("format") ? message.get("format").getAsString() : "opus";
        String dataB64 = message.has("data") ? message.get("data").getAsString() : null;
        if (dataB64 == null) return null;
        byte[] data = Base64.getDecoder().decode(dataB64);
        if ("opus".equals(format)) {
            vc.queueOpusFrame(data);
        }
        return null;
    }

    private JsonObject handleVoiceConfig(JsonObject message) {
        VoicechatIntegration vc = mod.getVoicechatIntegration();
        if (vc == null) return createError("Voice chat not available");
        boolean enabled = !message.has("enabled") || message.get("enabled").getAsBoolean();
        String channelType = message.has("channel_type") ? message.get("channel_type").getAsString() : "static";
        double distance = message.has("distance") ? message.get("distance").getAsDouble() : 100.0;
        String zone = message.has("zone") ? message.get("zone").getAsString() : "main";
        vc.setConfig(enabled, channelType, distance, zone);
        return vc.getStatus();
    }

    private JsonObject handleGetVoiceStatus() {
        VoicechatIntegration vc = mod.getVoicechatIntegration();
        if (vc == null) {
            JsonObject response = new JsonObject();
            response.addProperty("type", "voice_status");
            response.addProperty("available", false);
            return response;
        }
        return vc.getStatus();
    }

    // --- Stage serialization helpers ---

    private JsonObject stageToJson(Stage stage) {
        JsonObject json = new JsonObject();
        json.addProperty("name", stage.getName());
        json.addProperty("id", stage.getId().toString());
        json.addProperty("template", stage.getTemplateName());
        json.addProperty("active", stage.isActive());
        json.addProperty("rotation", stage.getRotation());

        JsonObject anchorJson = new JsonObject();
        anchorJson.addProperty("world", stage.getWorldName());
        anchorJson.addProperty("x", stage.getAnchor().getX());
        anchorJson.addProperty("y", stage.getAnchor().getY());
        anchorJson.addProperty("z", stage.getAnchor().getZ());
        json.add("anchor", anchorJson);

        JsonObject zonesJson = new JsonObject();
        for (var entry : stage.getRoleToZone().entrySet()) {
            StageZoneRole role = entry.getKey();
            String zoneName = entry.getValue();

            JsonObject zoneJson = new JsonObject();
            zoneJson.addProperty("zone_name", zoneName);
            zoneJson.addProperty("role", role.name());
            zoneJson.addProperty("display_name", role.getDisplayName());

            StageZoneConfig config = stage.getZoneConfigs().get(role);
            if (config != null) {
                zoneJson.add("config", stageZoneConfigToJson(config));
                zoneJson.addProperty("entity_count", config.getEntityCount());
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

    // --- Stage detail handlers ---

    private JsonObject handleGetStage(JsonObject message) {
        if (!message.has("name")) return createError("Missing required field: name");
        String name = message.get("name").getAsString();
        StageManager sm = mod.getStageManager();
        if (sm == null) return createError("Stage system not initialized");
        Stage stage = sm.getStage(name);
        if (stage == null) return createError("Stage not found: " + name);

        JsonObject response = new JsonObject();
        response.addProperty("type", "stage");
        response.add("stage", stageToJson(stage));
        return response;
    }

    private JsonObject handleGetStageTemplates() {
        StageManager sm = mod.getStageManager();
        if (sm == null) return createError("Stage system not initialized");

        JsonObject response = new JsonObject();
        response.addProperty("type", "stage_templates");

        JsonArray templatesArray = new JsonArray();
        for (var entry : sm.getAllTemplates().entrySet()) {
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

    private JsonObject handleUpdateStage(JsonObject message) {
        if (!message.has("name")) return createError("Missing required field: name");
        String name = message.get("name").getAsString();
        StageManager sm = mod.getStageManager();
        if (sm == null) return createError("Stage system not initialized");
        Stage stage = sm.getStage(name);
        if (stage == null) return createError("Stage not found: " + name);

        if (message.has("anchor")) {
            JsonObject anchorJson = message.getAsJsonObject("anchor");
            String worldName = anchorJson.has("world") ? anchorJson.get("world").getAsString() : stage.getWorldName();
            int x = anchorJson.has("x") ? anchorJson.get("x").getAsInt() : stage.getAnchor().getX();
            int y = anchorJson.has("y") ? anchorJson.get("y").getAsInt() : stage.getAnchor().getY();
            int z = anchorJson.has("z") ? anchorJson.get("z").getAsInt() : stage.getAnchor().getZ();
            sm.moveStage(stage, new BlockPos(x, y, z), worldName);
        }

        if (message.has("rotation")) {
            float rotation = message.get("rotation").getAsFloat();
            sm.rotateStage(stage, rotation);
        }

        if (message.has("add_role")) {
            String roleName = message.get("add_role").getAsString();
            try {
                StageZoneRole role = StageZoneRole.valueOf(roleName.toUpperCase());
                sm.addRoleToStage(stage, role);
            } catch (IllegalArgumentException e) {
                return createError("Unknown zone role: " + roleName);
            }
        }

        if (message.has("remove_role")) {
            String roleName = message.get("remove_role").getAsString();
            try {
                StageZoneRole role = StageZoneRole.valueOf(roleName.toUpperCase());
                sm.removeRoleFromStage(stage, role);
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
        if (!isValidZoneName(stageName)) return createError("Invalid stage name");
        String roleName = message.get("role").getAsString();

        StageManager sm = mod.getStageManager();
        if (sm == null) return createError("Stage system not initialized");
        Stage stage = sm.getStage(stageName);
        if (stage == null) return createError("Stage not found: " + stageName);

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

        if (configJson.has("pattern")) config.setPattern(configJson.get("pattern").getAsString());
        if (configJson.has("entity_count")) config.setEntityCount(configJson.get("entity_count").getAsInt());
        if (configJson.has("render_mode")) config.setRenderMode(configJson.get("render_mode").getAsString());
        if (configJson.has("block_type")) config.setBlockType(configJson.get("block_type").getAsString());
        if (configJson.has("brightness")) config.setBrightness(configJson.get("brightness").getAsInt());
        if (configJson.has("glow_on_beat")) config.setGlowOnBeat(configJson.get("glow_on_beat").getAsBoolean());
        if (configJson.has("intensity_multiplier")) config.setIntensityMultiplier(configJson.get("intensity_multiplier").getAsFloat());

        sm.saveStages();

        // Apply to the live entity pool if stage is active
        if (stage.isActive()) {
            String zoneName = stage.getRoleToZone().get(role);
            VisualizationZone vizZone = mod.getZoneManager().getZone(zoneName);
            if (vizZone != null) {
                BlockState blockState = com.audioviz.render.MaterialResolver.resolve(config.getBlockType());
                mod.getVirtualRenderer().initializePool(
                    zoneName, vizZone, config.getEntityCount(), vizZone.getWorld(), blockState);
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "stage_zone_config_updated");
        response.addProperty("stage", stageName);
        response.addProperty("role", role.name());
        response.add("config", stageZoneConfigToJson(config));
        return response;
    }

    // --- Block scanner ---

    /**
     * Scan all non-air blocks in the bounding box around a stage's zones.
     * Returns palette-compressed block data for 3D preview rendering.
     *
     * Block access requires the server thread — the caller (VizWebSocketServer)
     * already dispatches to the server thread, so we call the scan directly.
     */
    private JsonObject handleScanStageBlocks(JsonObject message) {
        if (!message.has("stage")) return createError("Missing required field: stage");
        String stageName = message.get("stage").getAsString();
        if (!isValidZoneName(stageName)) return createError("Invalid stage name");

        StageManager sm = mod.getStageManager();
        if (sm == null) return createError("Stage system not initialized");
        Stage stage = sm.getStage(stageName);
        if (stage == null) return createError("Stage not found: " + stageName);

        // Already on the server thread (VizWebSocketServer dispatches via server.execute()),
        // so call the scan directly — no need for a second server.execute() which would deadlock.
        try {
            return scanStageBlocksSync(stage, stageName);
        } catch (Exception e) {
            LOGGER.warn("Stage block scan failed for {}: {}", stageName, e.getMessage());
            return createError("Scan failed: " + e.getMessage());
        }
    }

    /**
     * Perform the actual block scan on the server thread.
     */
    private JsonObject scanStageBlocksSync(Stage stage, String stageName) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        ServerWorld world = null;

        for (String zoneName : stage.getZoneNames()) {
            VisualizationZone zone = mod.getZoneManager().getZone(zoneName);
            if (zone == null) continue;

            if (world == null) world = zone.getWorld();

            int ox = zone.getOrigin().getX();
            int oy = zone.getOrigin().getY();
            int oz = zone.getOrigin().getZ();
            Vector3f size = zone.getSize();
            int ex = ox + (int) Math.ceil(size.x);
            int ey = oy + (int) Math.ceil(size.y);
            int ez = oz + (int) Math.ceil(size.z);

            minX = Math.min(minX, ox);
            minY = Math.min(minY, oy);
            minZ = Math.min(minZ, oz);
            maxX = Math.max(maxX, ex);
            maxY = Math.max(maxY, ey);
            maxZ = Math.max(maxZ, ez);
        }

        if (world == null) return createError("No zones found for stage: " + stageName);

        // Expand bounding box: +5 XZ, +3 below, +2 above
        minX -= 5;
        minZ -= 5;
        maxX += 5;
        maxZ += 5;
        minY -= 3;
        maxY += 2;

        // Scan blocks — build palette and block array
        LinkedHashMap<String, Integer> palette = new LinkedHashMap<>();
        JsonArray blocksArray = new JsonArray();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) continue;

                    String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
                    // Convert to uppercase material-style name for compatibility
                    // e.g. "minecraft:stone" → "STONE"
                    String matName = blockId.contains(":") ? blockId.substring(blockId.indexOf(':') + 1).toUpperCase() : blockId.toUpperCase();
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

        LOGGER.info("Scanned stage '{}': {} blocks, {} materials", stageName, blocksArray.size(), palette.size());
        return response;
    }

    // --- Utilities ---

    private JsonObject createError(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "error");
        error.addProperty("message", message);
        return error;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Convert a zone rotation (degrees) to the nearest cardinal direction for display facing. */
    public static Direction directionFromRotation(float rotation) {
        float r = ((rotation % 360) + 360) % 360;
        if (r >= 315 || r < 45) return Direction.NORTH;
        if (r < 135) return Direction.EAST;
        if (r < 225) return Direction.SOUTH;
        return Direction.WEST;
    }

    // --- Accessors ---

    public AudioState getLatestAudioState() {
        return latestAudioState;
    }

    public void setBatchUpdateHandler(BatchUpdateHandler handler) {
        this.batchUpdateHandler = handler;
    }

    public void setBitmapFrameHandler(BitmapFrameHandler handler) {
        this.bitmapFrameHandler = handler;
    }

    // --- Callback interfaces ---

    @FunctionalInterface
    public interface BatchUpdateHandler {
        JsonObject handle(JsonObject message);
    }

    @FunctionalInterface
    public interface BitmapFrameHandler {
        JsonObject handle(JsonObject message);
    }
}
