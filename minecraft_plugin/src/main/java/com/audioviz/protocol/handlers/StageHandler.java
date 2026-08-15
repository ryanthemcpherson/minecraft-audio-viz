package com.audioviz.protocol.handlers;

import com.audioviz.AudioVizPlugin;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageManager;
import com.audioviz.stages.StageTemplate;
import com.audioviz.stages.StageZoneConfig;
import com.audioviz.stages.StageZoneRole;
import com.audioviz.zones.VisualizationZone;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles stage management messages:
 * get_stages, get_stage, create_stage, delete_stage, activate_stage,
 * deactivate_stage, update_stage, set_stage_zone_config, get_stage_templates,
 * scan_stage_blocks.
 */
public class StageHandler extends BaseHandler {

    public StageHandler(AudioVizPlugin plugin) {
        super(plugin);
    }

    @Override
    public String[] getMessageTypes() {
        return new String[]{
            "get_stages", "get_stage", "create_stage", "delete_stage",
            "activate_stage", "deactivate_stage", "update_stage",
            "set_stage_zone_config", "get_stage_templates", "scan_stage_blocks"
        };
    }

    @Override
    public JsonObject handle(String type, JsonObject message) {
        return switch (type) {
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
            default -> createError("Unknown message type: " + type);
        };
    }

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

    /**
     * Scan all non-air blocks in the bounding box around a stage's zones.
     * Returns palette-compressed block data for 3D preview rendering.
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

    // ========== JSON Helpers ==========

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
}
