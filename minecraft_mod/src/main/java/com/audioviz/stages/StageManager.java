package com.audioviz.stages;

import com.audioviz.AudioVizMod;
import com.audioviz.decorators.DecoratorConfig;
import com.audioviz.zones.VisualizationZone;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages stage lifecycle: creation from templates, persistence, zone orchestration.
 *
 * <p>Ported from Paper: YAML config → JSON (Gson), Bukkit.getWorld() → server.getWorlds(),
 * Location → BlockPos + worldName, AudioVizPlugin → AudioVizMod.
 */
public class StageManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final AudioVizMod mod;
    private final Map<String, Stage> stages;
    private final Path stagesFile;

    public StageManager(AudioVizMod mod) {
        this.mod = mod;
        this.stages = new ConcurrentHashMap<>();
        this.stagesFile = mod.getConfigDir().resolve("stages.json");
    }

    // ========== Stage CRUD ==========

    public Stage createStage(String name, BlockPos anchor, String worldName, String templateName) {
        if (stages.containsKey(name.toLowerCase())) return null;

        Stage stage = new Stage(name, anchor, worldName, templateName);

        // Create zones based on template type
        Set<StageZoneRole> roles = getTemplateRoles(templateName);
        float sizeScale = getTemplateScale(templateName);

        for (StageZoneRole role : roles) {
            String zoneName = name.toLowerCase() + "_" + role.name().toLowerCase();
            Vec3d worldPos = stage.getWorldPositionForRole(role);
            BlockPos zoneOrigin = new BlockPos((int) worldPos.x, (int) worldPos.y, (int) worldPos.z);

            ServerWorld world = findWorld(worldName);
            if (world == null) continue;

            VisualizationZone zone = mod.getZoneManager().createZone(zoneName, world, zoneOrigin);
            if (zone != null) {
                Vector3f size = role.getDefaultSize();
                zone.setSize(size.x * sizeScale, size.y * sizeScale, size.z * sizeScale);

                stage.getRoleToZone().put(role, zoneName);

                StageZoneConfig config = new StageZoneConfig();
                config.setPattern(role.getSuggestedPattern());
                stage.getZoneConfigs().put(role, config);
            }
        }

        mod.getZoneManager().saveZones();
        stages.put(name.toLowerCase(), stage);
        saveStages();

        LOGGER.info("Created stage '{}' from template '{}' with {} zones",
            name, templateName, stage.getRoleToZone().size());

        return stage;
    }

    public boolean deleteStage(String name) {
        Stage stage = stages.remove(name.toLowerCase());
        if (stage == null) return false;

        for (String zoneName : stage.getZoneNames()) {
            // Clean up renderers and bitmap state before deleting the zone
            try {
                mod.getMapRenderer().destroyDisplay(zoneName);
                mod.getBitmapToEntityBridge().destroyWall(zoneName);
                mod.getVirtualRenderer().destroyPool(zoneName);
                var bpm = mod.getBitmapPatternManager();
                if (bpm != null) bpm.deactivateZone(zoneName);
            } catch (Exception e) {
                LOGGER.warn("Error cleaning up zone '{}' during stage delete: {}", zoneName, e.getMessage());
            }
            mod.getZoneManager().deleteZone(zoneName);
        }

        saveStages();
        LOGGER.info("Deleted stage '{}'", name);
        return true;
    }

    public void activateStage(Stage stage) {
        stage.setActive(true);
        stage.setLastActivatedAt(System.currentTimeMillis());
        saveStages();
        LOGGER.info("Activated stage '{}'", stage.getName());
    }

    public void deactivateStage(Stage stage) {
        stage.setActive(false);
        saveStages();
        LOGGER.info("Deactivated stage '{}'", stage.getName());
    }

    // ========== Lookup ==========

    public Stage getStage(String name) { return stages.get(name.toLowerCase()); }
    public Collection<Stage> getAllStages() { return Collections.unmodifiableCollection(stages.values()); }
    public int getStageCount() { return stages.size(); }
    public boolean stageExists(String name) { return stages.containsKey(name.toLowerCase()); }
    public Set<String> getStageNames() { return Collections.unmodifiableSet(stages.keySet()); }

    public Stage findStageForZone(String zoneName) {
        for (Stage stage : stages.values()) {
            if (stage.ownsZone(zoneName)) return stage;
        }
        return null;
    }

    // ========== Persistence (JSON) ==========

    public void loadStages() {
        if (!stagesFile.toFile().exists()) {
            LOGGER.info("No stages file found, starting fresh.");
            return;
        }

        try (Reader reader = new InputStreamReader(
                new FileInputStream(stagesFile.toFile()), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                String stageName = entry.getKey();
                JsonObject data = entry.getValue().getAsJsonObject();

                try {
                    UUID id = UUID.fromString(getStr(data, "id", UUID.randomUUID().toString()));
                    String templateName = getStr(data, "template", "custom");
                    boolean active = getBool(data, "active", false);
                    float rotation = getFloat(data, "rotation", 0f);
                    String worldName = getStr(data, "world", "minecraft:overworld");

                    JsonObject anchorObj = data.has("anchor") ? data.getAsJsonObject("anchor") : new JsonObject();
                    BlockPos anchor = new BlockPos(
                        getInt(anchorObj, "x", 0),
                        getInt(anchorObj, "y", 64),
                        getInt(anchorObj, "z", 0)
                    );

                    Map<StageZoneRole, String> roleToZone = new EnumMap<>(StageZoneRole.class);
                    Map<StageZoneRole, StageZoneConfig> zoneConfigs = new EnumMap<>(StageZoneRole.class);

                    if (data.has("zones")) {
                        JsonObject zonesObj = data.getAsJsonObject("zones");
                        for (Map.Entry<String, JsonElement> zoneEntry : zonesObj.entrySet()) {
                            try {
                                StageZoneRole role = StageZoneRole.valueOf(zoneEntry.getKey().toUpperCase());
                                JsonObject zoneData = zoneEntry.getValue().getAsJsonObject();

                                String zoneName = getStr(zoneData, "zone_name", null);
                                if (zoneName != null) roleToZone.put(role, zoneName);

                                if (zoneData.has("config")) {
                                    JsonObject configData = zoneData.getAsJsonObject("config");
                                    StageZoneConfig config = new StageZoneConfig();
                                    config.setPattern(getStr(configData, "pattern", "spectrum"));
                                    config.setEntityCount(getInt(configData, "entity_count", 16));
                                    config.setRenderMode(getStr(configData, "render_mode", "entities"));
                                    config.setBlockType(getStr(configData, "block_type", "sea_lantern"));
                                    config.setBrightness(getInt(configData, "brightness", 15));
                                    config.setGlowOnBeat(getBool(configData, "glow_on_beat", false));
                                    config.setIntensityMultiplier(getFloat(configData, "intensity_multiplier", 1.0f));
                                    zoneConfigs.put(role, config);
                                }
                            } catch (IllegalArgumentException ignored) {}
                        }
                    }

                    Map<String, DecoratorConfig> decoratorConfigs = new LinkedHashMap<>();
                    if (data.has("decorators")) {
                        JsonObject decosObj = data.getAsJsonObject("decorators");
                        for (Map.Entry<String, JsonElement> decoEntry : decosObj.entrySet()) {
                            JsonObject decoData = decoEntry.getValue().getAsJsonObject();
                            boolean decoEnabled = getBool(decoData, "enabled", true);
                            Map<String, Object> settings = new LinkedHashMap<>();
                            if (decoData.has("settings")) {
                                for (Map.Entry<String, JsonElement> s : decoData.getAsJsonObject("settings").entrySet()) {
                                    JsonElement el = s.getValue();
                                    if (el.isJsonPrimitive()) {
                                        JsonPrimitive p = el.getAsJsonPrimitive();
                                        if (p.isBoolean()) settings.put(s.getKey(), p.getAsBoolean());
                                        else if (p.isNumber()) settings.put(s.getKey(), p.getAsDouble());
                                        else settings.put(s.getKey(), p.getAsString());
                                    }
                                }
                            }
                            decoratorConfigs.put(decoEntry.getKey(), new DecoratorConfig(decoEnabled, settings));
                        }
                    }

                    String tag = getStr(data, "tag", "");
                    boolean pinned = getBool(data, "pinned", false);
                    long createdAt = getLong(data, "created_at", System.currentTimeMillis());
                    long lastActivatedAt = getLong(data, "last_activated_at", 0L);

                    Stage stage = new Stage(stageName, id, anchor, worldName, rotation, templateName,
                        active, roleToZone, zoneConfigs, decoratorConfigs,
                        tag, pinned, createdAt, lastActivatedAt);
                    stages.put(stageName.toLowerCase(), stage);

                    LOGGER.info("Loaded stage: {}", stage);
                } catch (Exception e) {
                    LOGGER.warn("Failed to load stage '{}': {}", stageName, e.getMessage());
                }
            }

            LOGGER.info("Loaded {} stage(s)", stages.size());
        } catch (Exception e) {
            LOGGER.warn("Failed to read stages file: {}", e.getMessage());
        }
    }

    public void saveStages() {
        JsonObject root = new JsonObject();

        for (Stage stage : stages.values()) {
            JsonObject data = new JsonObject();
            data.addProperty("id", stage.getId().toString());
            data.addProperty("template", stage.getTemplateName());
            data.addProperty("active", stage.isActive());
            data.addProperty("rotation", stage.getRotation());
            data.addProperty("world", stage.getWorldName());
            data.addProperty("tag", stage.getTag());
            data.addProperty("pinned", stage.isPinned());
            data.addProperty("created_at", stage.getCreatedAt());
            data.addProperty("last_activated_at", stage.getLastActivatedAt());

            JsonObject anchorObj = new JsonObject();
            anchorObj.addProperty("x", stage.getAnchor().getX());
            anchorObj.addProperty("y", stage.getAnchor().getY());
            anchorObj.addProperty("z", stage.getAnchor().getZ());
            data.add("anchor", anchorObj);

            JsonObject zonesObj = new JsonObject();
            for (Map.Entry<StageZoneRole, String> entry : stage.getRoleToZone().entrySet()) {
                JsonObject zoneData = new JsonObject();
                zoneData.addProperty("zone_name", entry.getValue());
                StageZoneConfig config = stage.getZoneConfigs().get(entry.getKey());
                if (config != null) {
                    JsonObject configObj = new JsonObject();
                    configObj.addProperty("pattern", config.getPattern());
                    configObj.addProperty("entity_count", config.getEntityCount());
                    configObj.addProperty("render_mode", config.getRenderMode());
                    configObj.addProperty("block_type", config.getBlockType());
                    configObj.addProperty("brightness", config.getBrightness());
                    configObj.addProperty("glow_on_beat", config.isGlowOnBeat());
                    configObj.addProperty("intensity_multiplier", config.getIntensityMultiplier());
                    zoneData.add("config", configObj);
                }
                zonesObj.add(entry.getKey().name(), zoneData);
            }
            data.add("zones", zonesObj);

            if (!stage.getDecoratorConfigs().isEmpty()) {
                JsonObject decosObj = new JsonObject();
                for (Map.Entry<String, DecoratorConfig> entry : stage.getDecoratorConfigs().entrySet()) {
                    JsonObject decoObj = new JsonObject();
                    decoObj.addProperty("enabled", entry.getValue().isEnabled());
                    JsonObject settingsObj = new JsonObject();
                    for (Map.Entry<String, Object> s : entry.getValue().getSettings().entrySet()) {
                        if (s.getValue() instanceof Boolean b) settingsObj.addProperty(s.getKey(), b);
                        else if (s.getValue() instanceof Number n) settingsObj.addProperty(s.getKey(), n);
                        else settingsObj.addProperty(s.getKey(), String.valueOf(s.getValue()));
                    }
                    decoObj.add("settings", settingsObj);
                    decosObj.add(entry.getKey(), decoObj);
                }
                data.add("decorators", decosObj);
            }

            root.add(stage.getName(), data);
        }

        try {
            stagesFile.getParent().toFile().mkdirs();
            try (Writer writer = new OutputStreamWriter(
                    new FileOutputStream(stagesFile.toFile()), StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save stages: {}", e.getMessage());
        }
    }

    // ========== Template Helpers ==========

    private static Set<StageZoneRole> getTemplateRoles(String template) {
        return switch (template.toLowerCase()) {
            case "small" -> EnumSet.of(
                StageZoneRole.MAIN_STAGE,
                StageZoneRole.AUDIENCE
            );
            case "medium" -> EnumSet.of(
                StageZoneRole.MAIN_STAGE,
                StageZoneRole.LEFT_WING,
                StageZoneRole.RIGHT_WING,
                StageZoneRole.AUDIENCE,
                StageZoneRole.SKYBOX
            );
            default -> EnumSet.allOf(StageZoneRole.class); // large, custom
        };
    }

    private static float getTemplateScale(String template) {
        return switch (template.toLowerCase()) {
            case "small" -> 0.6f;
            case "large" -> 1.5f;
            default -> 1.0f; // medium, custom
        };
    }

    /** Get the roles a template creates (for UI descriptions). */
    public static Set<StageZoneRole> getTemplateRolesPublic(String template) {
        return getTemplateRoles(template);
    }

    // ========== World Helpers ==========

    private ServerWorld findWorld(String worldName) {
        for (ServerWorld w : mod.getServer().getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(worldName)) return w;
        }
        // Fallback: try matching just the path (e.g., "overworld" matches "minecraft:overworld")
        for (ServerWorld w : mod.getServer().getWorlds()) {
            if (w.getRegistryKey().getValue().getPath().equals(worldName)) return w;
        }
        LOGGER.warn("World '{}' not found", worldName);
        return null;
    }

    private static String getStr(JsonObject obj, String key, String def) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : def;
    }
    private static int getInt(JsonObject obj, String key, int def) {
        return obj.has(key) ? obj.get(key).getAsInt() : def;
    }
    private static float getFloat(JsonObject obj, String key, float def) {
        return obj.has(key) ? obj.get(key).getAsFloat() : def;
    }
    private static boolean getBool(JsonObject obj, String key, boolean def) {
        return obj.has(key) ? obj.get(key).getAsBoolean() : def;
    }
    private static long getLong(JsonObject obj, String key, long def) {
        return obj.has(key) ? obj.get(key).getAsLong() : def;
    }
}
