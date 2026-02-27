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
import com.audioviz.stages.StageZoneRole;
import com.audioviz.voice.VoicechatIntegration;
import com.audioviz.zones.VisualizationZone;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
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
            JsonObject z = new JsonObject();
            z.addProperty("name", zone.getName());
            z.addProperty("id", zone.getId().toString());
            z.addProperty("x", zone.getOrigin().getX());
            z.addProperty("y", zone.getOrigin().getY());
            z.addProperty("z_pos", zone.getOrigin().getZ());
            z.addProperty("size_x", zone.getSize().x);
            z.addProperty("size_y", zone.getSize().y);
            z.addProperty("size_z", zone.getSize().z);
            z.addProperty("rotation", zone.getRotation());
            zonesArr.add(z);
        }
        response.add("zones", zonesArr);
        return response;
    }

    private JsonObject handleGetZone(JsonObject message) {
        String name = message.has("zone") ? message.get("zone").getAsString() : null;
        if (name == null || !isValidZoneName(name)) return createError("Invalid zone name");
        VisualizationZone zone = mod.getZoneManager().getZone(name);
        if (zone == null) return createError("Zone not found: " + name);
        JsonObject response = new JsonObject();
        response.addProperty("type", "zone");
        response.addProperty("name", zone.getName());
        response.addProperty("x", zone.getOrigin().getX());
        response.addProperty("y", zone.getOrigin().getY());
        response.addProperty("z_pos", zone.getOrigin().getZ());
        response.addProperty("size_x", zone.getSize().x);
        response.addProperty("size_y", zone.getSize().y);
        response.addProperty("size_z", zone.getSize().z);
        response.addProperty("rotation", zone.getRotation());
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

        // Acknowledge — the VJ server tracks which backend is active and
        // routes batch_update vs bitmap_frame messages accordingly.
        // The Minecraft side just needs the corresponding renderer initialized.
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
