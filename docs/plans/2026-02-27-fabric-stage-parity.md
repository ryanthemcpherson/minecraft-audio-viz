## Status: COMPLETED
# Fabric Mod Stage Handler Parity Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Port 5 missing stage message handlers from the Paper plugin to the Fabric mod so the control center (admin panel + preview tool) has full stage management and world scanning support.

**Architecture:** Add `get_stage`, `update_stage`, `set_stage_zone_config`, `get_stage_templates`, and `scan_stage_blocks` handlers to the existing `MessageHandler.java`. Add `StageTemplate.java` for template definitions (Fabric-native, no Bukkit deps). Add `moveStage()`, `rotateStage()`, `addRoleToStage()`, `removeRoleFromStage()` to `StageManager.java`. All response formats match the Paper plugin exactly.

**Tech Stack:** Java 21, Fabric 1.21.11, Minecraft `net.minecraft.world`, `net.minecraft.block`, `net.minecraft.registry.Registries`

**Key files to reference:**
- Paper plugin reference: `minecraft_plugin/src/main/java/com/audioviz/protocol/MessageHandler.java`
- Paper StageTemplate: `minecraft_plugin/src/main/java/com/audioviz/stages/StageTemplate.java`
- Mod MessageHandler: `minecraft_mod/src/main/java/com/audioviz/protocol/MessageHandler.java`
- Mod StageManager: `minecraft_mod/src/main/java/com/audioviz/stages/StageManager.java`
- Mod Stage: `minecraft_mod/src/main/java/com/audioviz/stages/Stage.java`
- Mod StageZoneRole: `minecraft_mod/src/main/java/com/audioviz/stages/StageZoneRole.java`
- Mod StageZoneConfig: `minecraft_mod/src/main/java/com/audioviz/stages/StageZoneConfig.java`
- Mod VisualizationZone: `minecraft_mod/src/main/java/com/audioviz/zones/VisualizationZone.java`

---

### Task 1: Add StageTemplate class (Fabric-native)

**Files:**
- Create: `minecraft_mod/src/main/java/com/audioviz/stages/StageTemplate.java`

**Step 1: Create StageTemplate.java**

Port the Paper plugin's `StageTemplate.java` to Fabric. Replace `org.bukkit.Material` with `String` (icon block name), and `org.bukkit.util.Vector` with `org.joml.Vector3f`. Keep the builder pattern and all 4 built-in templates (club, concert, arena, festival).

```java
package com.audioviz.stages;

import org.joml.Vector3f;

import java.util.*;
import java.util.function.Consumer;

/**
 * Defines a predefined stage layout template.
 * Ported from Paper: Material → String (icon block), Vector → Vector3f.
 */
public class StageTemplate {

    private final String name;
    private final String description;
    private final String iconBlock;
    private final Set<StageZoneRole> roles;
    private final Map<StageZoneRole, Vector3f> sizeOverrides;
    private final Map<StageZoneRole, Vector3f> offsetOverrides;
    private final Map<StageZoneRole, StageZoneConfig> defaultConfigs;
    private final int defaultEntityCount;

    private StageTemplate(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.iconBlock = builder.iconBlock;
        this.roles = Collections.unmodifiableSet(new LinkedHashSet<>(builder.roles));
        this.sizeOverrides = Collections.unmodifiableMap(new EnumMap<>(builder.sizeOverrides));
        this.offsetOverrides = Collections.unmodifiableMap(new EnumMap<>(builder.offsetOverrides));
        this.defaultConfigs = Collections.unmodifiableMap(new EnumMap<>(builder.defaultConfigs));
        this.defaultEntityCount = builder.defaultEntityCount;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getIconBlock() { return iconBlock; }
    public Set<StageZoneRole> getRoles() { return roles; }
    public int getRoleCount() { return roles.size(); }
    public int getDefaultEntityCount() { return defaultEntityCount; }

    public Vector3f getSizeForRole(StageZoneRole role) {
        return sizeOverrides.getOrDefault(role, role.getDefaultSize());
    }

    public Vector3f getOffsetForRole(StageZoneRole role) {
        return offsetOverrides.getOrDefault(role, role.getDefaultOffset());
    }

    public int getEstimatedEntityCount() {
        int total = 0;
        for (StageZoneRole role : roles) {
            StageZoneConfig config = defaultConfigs.get(role);
            total += (config != null) ? config.getEntityCount() : defaultEntityCount;
        }
        return total;
    }

    public static Builder builder(String name) { return new Builder(name); }

    // ========== Built-in Templates ==========

    private static final Map<String, StageTemplate> BUILTIN_TEMPLATES = new LinkedHashMap<>();

    static {
        BUILTIN_TEMPLATES.put("club", builder("club")
            .description("Small intimate setup with main stage and audience")
            .iconBlock("note_block")
            .defaultEntityCount(16)
            .role(StageZoneRole.MAIN_STAGE)
                .sizeOverride(StageZoneRole.MAIN_STAGE, new Vector3f(8, 8, 6))
                .configOverride(StageZoneRole.MAIN_STAGE, c -> { c.setEntityCount(16); c.setPattern("spectrum"); })
            .role(StageZoneRole.AUDIENCE)
                .sizeOverride(StageZoneRole.AUDIENCE, new Vector3f(12, 3, 8))
                .configOverride(StageZoneRole.AUDIENCE, c -> { c.setEntityCount(8); c.setPattern("wave"); })
            .build());

        BUILTIN_TEMPLATES.put("concert", builder("concert")
            .description("Standard concert with wings, skybox, and audience")
            .iconBlock("jukebox")
            .defaultEntityCount(16)
            .role(StageZoneRole.MAIN_STAGE)
                .configOverride(StageZoneRole.MAIN_STAGE, c -> { c.setEntityCount(32); c.setPattern("spectrum"); })
            .role(StageZoneRole.LEFT_WING)
                .configOverride(StageZoneRole.LEFT_WING, c -> { c.setEntityCount(16); c.setPattern("laser"); })
            .role(StageZoneRole.RIGHT_WING)
                .configOverride(StageZoneRole.RIGHT_WING, c -> { c.setEntityCount(16); c.setPattern("laser"); })
            .role(StageZoneRole.SKYBOX)
                .configOverride(StageZoneRole.SKYBOX, c -> { c.setEntityCount(16); c.setPattern("aurora"); })
            .role(StageZoneRole.AUDIENCE)
                .configOverride(StageZoneRole.AUDIENCE, c -> { c.setEntityCount(16); c.setPattern("wave"); })
            .build());

        BUILTIN_TEMPLATES.put("arena", builder("arena")
            .description("Large arena with backstage, runway, and full surround")
            .iconBlock("end_portal_frame")
            .defaultEntityCount(24)
            .role(StageZoneRole.MAIN_STAGE)
                .sizeOverride(StageZoneRole.MAIN_STAGE, new Vector3f(20, 16, 12))
                .configOverride(StageZoneRole.MAIN_STAGE, c -> { c.setEntityCount(64); c.setPattern("spectrum"); })
            .role(StageZoneRole.LEFT_WING)
                .sizeOverride(StageZoneRole.LEFT_WING, new Vector3f(10, 12, 10))
                .offsetOverride(StageZoneRole.LEFT_WING, new Vector3f(-18, 0, 0))
                .configOverride(StageZoneRole.LEFT_WING, c -> { c.setEntityCount(24); c.setPattern("laser"); })
            .role(StageZoneRole.RIGHT_WING)
                .sizeOverride(StageZoneRole.RIGHT_WING, new Vector3f(10, 12, 10))
                .offsetOverride(StageZoneRole.RIGHT_WING, new Vector3f(18, 0, 0))
                .configOverride(StageZoneRole.RIGHT_WING, c -> { c.setEntityCount(24); c.setPattern("laser"); })
            .role(StageZoneRole.SKYBOX)
                .sizeOverride(StageZoneRole.SKYBOX, new Vector3f(30, 8, 20))
                .offsetOverride(StageZoneRole.SKYBOX, new Vector3f(0, 18, 0))
                .configOverride(StageZoneRole.SKYBOX, c -> { c.setEntityCount(32); c.setPattern("aurora"); })
            .role(StageZoneRole.AUDIENCE)
                .sizeOverride(StageZoneRole.AUDIENCE, new Vector3f(30, 4, 16))
                .offsetOverride(StageZoneRole.AUDIENCE, new Vector3f(0, 0, 18))
                .configOverride(StageZoneRole.AUDIENCE, c -> { c.setEntityCount(24); c.setPattern("wave"); })
            .role(StageZoneRole.BACKSTAGE)
                .sizeOverride(StageZoneRole.BACKSTAGE, new Vector3f(12, 8, 8))
                .offsetOverride(StageZoneRole.BACKSTAGE, new Vector3f(0, 0, -14))
                .configOverride(StageZoneRole.BACKSTAGE, c -> { c.setEntityCount(16); c.setPattern("bars"); })
            .role(StageZoneRole.RUNWAY)
                .sizeOverride(StageZoneRole.RUNWAY, new Vector3f(4, 8, 20))
                .configOverride(StageZoneRole.RUNWAY, c -> { c.setEntityCount(16); c.setPattern("vortex"); })
            .build());

        BUILTIN_TEMPLATES.put("festival", builder("festival")
            .description("Massive festival with all zones at 1.5x scale")
            .iconBlock("dragon_egg")
            .defaultEntityCount(32)
            .role(StageZoneRole.MAIN_STAGE)
                .sizeOverride(StageZoneRole.MAIN_STAGE, new Vector3f(30, 24, 18))
                .configOverride(StageZoneRole.MAIN_STAGE, c -> { c.setEntityCount(100); c.setPattern("spectrum"); })
            .role(StageZoneRole.LEFT_WING)
                .sizeOverride(StageZoneRole.LEFT_WING, new Vector3f(15, 18, 15))
                .offsetOverride(StageZoneRole.LEFT_WING, new Vector3f(-27, 0, 0))
                .configOverride(StageZoneRole.LEFT_WING, c -> { c.setEntityCount(36); c.setPattern("laser"); })
            .role(StageZoneRole.RIGHT_WING)
                .sizeOverride(StageZoneRole.RIGHT_WING, new Vector3f(15, 18, 15))
                .offsetOverride(StageZoneRole.RIGHT_WING, new Vector3f(27, 0, 0))
                .configOverride(StageZoneRole.RIGHT_WING, c -> { c.setEntityCount(36); c.setPattern("laser"); })
            .role(StageZoneRole.SKYBOX)
                .sizeOverride(StageZoneRole.SKYBOX, new Vector3f(45, 12, 30))
                .offsetOverride(StageZoneRole.SKYBOX, new Vector3f(0, 27, 0))
                .configOverride(StageZoneRole.SKYBOX, c -> { c.setEntityCount(48); c.setPattern("aurora"); })
            .role(StageZoneRole.AUDIENCE)
                .sizeOverride(StageZoneRole.AUDIENCE, new Vector3f(45, 6, 24))
                .offsetOverride(StageZoneRole.AUDIENCE, new Vector3f(0, 0, 27))
                .configOverride(StageZoneRole.AUDIENCE, c -> { c.setEntityCount(36); c.setPattern("wave"); })
            .role(StageZoneRole.BACKSTAGE)
                .sizeOverride(StageZoneRole.BACKSTAGE, new Vector3f(18, 12, 12))
                .offsetOverride(StageZoneRole.BACKSTAGE, new Vector3f(0, 0, -21))
                .configOverride(StageZoneRole.BACKSTAGE, c -> { c.setEntityCount(24); c.setPattern("bars"); })
            .role(StageZoneRole.RUNWAY)
                .sizeOverride(StageZoneRole.RUNWAY, new Vector3f(6, 12, 30))
                .offsetOverride(StageZoneRole.RUNWAY, new Vector3f(0, 0, 12))
                .configOverride(StageZoneRole.RUNWAY, c -> { c.setEntityCount(24); c.setPattern("vortex"); })
            .role(StageZoneRole.BALCONY)
                .sizeOverride(StageZoneRole.BALCONY, new Vector3f(30, 6, 9))
                .offsetOverride(StageZoneRole.BALCONY, new Vector3f(0, 12, 21))
                .configOverride(StageZoneRole.BALCONY, c -> { c.setEntityCount(24); c.setPattern("mandala"); })
            .build());
    }

    public static Map<String, StageTemplate> getBuiltinTemplates() {
        return Collections.unmodifiableMap(BUILTIN_TEMPLATES);
    }

    public static StageTemplate getBuiltin(String name) {
        return BUILTIN_TEMPLATES.get(name.toLowerCase());
    }

    // ========== Builder ==========

    public static class Builder {
        private final String name;
        private String description = "";
        private String iconBlock = "jukebox";
        private final Set<StageZoneRole> roles = new LinkedHashSet<>();
        private final Map<StageZoneRole, Vector3f> sizeOverrides = new EnumMap<>(StageZoneRole.class);
        private final Map<StageZoneRole, Vector3f> offsetOverrides = new EnumMap<>(StageZoneRole.class);
        private final Map<StageZoneRole, StageZoneConfig> defaultConfigs = new EnumMap<>(StageZoneRole.class);
        private int defaultEntityCount = 16;

        private Builder(String name) { this.name = name; }

        public Builder description(String d) { this.description = d; return this; }
        public Builder iconBlock(String i) { this.iconBlock = i; return this; }
        public Builder defaultEntityCount(int c) { this.defaultEntityCount = c; return this; }
        public Builder role(StageZoneRole role) { roles.add(role); return this; }
        public Builder sizeOverride(StageZoneRole role, Vector3f size) { sizeOverrides.put(role, size); return this; }
        public Builder offsetOverride(StageZoneRole role, Vector3f offset) { offsetOverrides.put(role, offset); return this; }
        public Builder configOverride(StageZoneRole role, Consumer<StageZoneConfig> configurer) {
            StageZoneConfig config = defaultConfigs.computeIfAbsent(role, k -> new StageZoneConfig());
            configurer.accept(config);
            return this;
        }
        public StageTemplate build() { return new StageTemplate(this); }
    }
}
```

**Step 2: Verify it compiles**

Run: `cd minecraft_mod && ./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add minecraft_mod/src/main/java/com/audioviz/stages/StageTemplate.java
git commit -m "feat(mod): add StageTemplate class with built-in templates"
```

---

### Task 2: Add moveStage, rotateStage, addRoleToStage, removeRoleFromStage to StageManager

**Files:**
- Modify: `minecraft_mod/src/main/java/com/audioviz/stages/StageManager.java`

**Step 1: Add 4 methods after `repositionZones()`**

Insert after the `repositionZones()` method (line ~140) and before `// ========== Lookup ==========`:

```java
    /**
     * Move a stage to a new anchor position and reposition all zones.
     */
    public void moveStage(Stage stage, BlockPos newAnchor, String worldName) {
        stage.setAnchor(newAnchor);
        if (worldName != null) stage.setWorldName(worldName);

        for (Map.Entry<StageZoneRole, String> entry : stage.getRoleToZone().entrySet()) {
            StageZoneRole role = entry.getKey();
            String zoneName = entry.getValue();
            VisualizationZone zone = mod.getZoneManager().getZone(zoneName);
            if (zone == null) continue;

            Vec3d worldPos = stage.getWorldPositionForRole(role);
            zone.setOrigin(new BlockPos((int) worldPos.x, (int) worldPos.y, (int) worldPos.z));

            if (worldName != null) {
                ServerWorld world = findWorld(worldName);
                if (world != null) zone.setWorld(world);
            }
        }

        mod.getZoneManager().saveZones();
        saveStages();
        LOGGER.info("Moved stage '{}' to ({}, {}, {})", stage.getName(),
            newAnchor.getX(), newAnchor.getY(), newAnchor.getZ());
    }

    /**
     * Rotate the entire stage and recalculate zone positions.
     */
    public void rotateStage(Stage stage, float newRotation) {
        stage.setRotation(newRotation);

        for (Map.Entry<StageZoneRole, String> entry : stage.getRoleToZone().entrySet()) {
            StageZoneRole role = entry.getKey();
            String zoneName = entry.getValue();
            VisualizationZone zone = mod.getZoneManager().getZone(zoneName);
            if (zone == null) continue;

            Vec3d worldPos = stage.getWorldPositionForRole(role);
            zone.setOrigin(new BlockPos((int) worldPos.x, (int) worldPos.y, (int) worldPos.z));
            zone.setRotation(newRotation);
        }

        mod.getZoneManager().saveZones();
        saveStages();
        LOGGER.info("Rotated stage '{}' to {}°", stage.getName(), newRotation);
    }

    /**
     * Add a zone role to an existing stage.
     */
    public boolean addRoleToStage(Stage stage, StageZoneRole role) {
        if (stage.getRoleToZone().containsKey(role)) return false;

        String zoneName = stage.getName().toLowerCase() + "_" + role.name().toLowerCase();
        Vec3d worldPos = stage.getWorldPositionForRole(role);
        BlockPos zoneOrigin = new BlockPos((int) worldPos.x, (int) worldPos.y, (int) worldPos.z);

        ServerWorld world = findWorld(stage.getWorldName());
        if (world == null) return false;

        VisualizationZone zone = mod.getZoneManager().createZone(zoneName, world, zoneOrigin);
        if (zone == null) return false;

        // Use template size override if available, otherwise default
        StageTemplate template = StageTemplate.getBuiltin(stage.getTemplateName());
        Vector3f size = (template != null) ? template.getSizeForRole(role) : role.getDefaultSize();
        zone.setSize(size);
        zone.setRotation(stage.getRotation());

        stage.getRoleToZone().put(role, zoneName);

        StageZoneConfig config = new StageZoneConfig();
        config.setPattern(role.getSuggestedPattern());
        stage.getZoneConfigs().put(role, config);

        mod.getZoneManager().saveZones();
        saveStages();
        LOGGER.info("Added role {} to stage '{}'", role.name(), stage.getName());
        return true;
    }

    /**
     * Remove a zone role from a stage.
     */
    public boolean removeRoleFromStage(Stage stage, StageZoneRole role) {
        String zoneName = stage.getRoleToZone().remove(role);
        if (zoneName == null) return false;

        stage.getZoneConfigs().remove(role);

        // Clean up renderers and subsystems
        try {
            mod.getMapRenderer().destroyDisplay(zoneName);
            if (mod.getBitmapToEntityBridge() != null) mod.getBitmapToEntityBridge().destroyWall(zoneName);
            mod.getVirtualRenderer().destroyPool(zoneName);
            var bpm = mod.getBitmapPatternManager();
            if (bpm != null) bpm.deactivateZone(zoneName);
            if (mod.getAmbientLightManager() != null) mod.getAmbientLightManager().teardownZone(zoneName);
            if (mod.getBeatEventManager() != null) mod.getBeatEventManager().removeZoneConfig(zoneName);
        } catch (Exception e) {
            LOGGER.warn("Error cleaning up zone '{}' during role removal: {}", zoneName, e.getMessage());
        }

        mod.getZoneManager().deleteZone(zoneName);
        saveStages();
        LOGGER.info("Removed role {} from stage '{}'", role.name(), stage.getName());
        return true;
    }
```

**Step 2: Add `getTemplate()` and `getAllTemplates()` methods**

Add in the lookup section:

```java
    public StageTemplate getTemplate(String name) {
        return StageTemplate.getBuiltin(name);
    }

    public Map<String, StageTemplate> getAllTemplates() {
        return StageTemplate.getBuiltinTemplates();
    }
```

**Step 3: Add import for StageTemplate**

The import `com.audioviz.stages.StageTemplate` is in the same package, so no import needed.

**Step 4: Verify it compiles**

Run: `cd minecraft_mod && ./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add minecraft_mod/src/main/java/com/audioviz/stages/StageManager.java
git commit -m "feat(mod): add moveStage, rotateStage, addRoleToStage, removeRoleFromStage to StageManager"
```

---

### Task 3: Add get_stage and get_stage_templates handlers to MessageHandler

**Files:**
- Modify: `minecraft_mod/src/main/java/com/audioviz/protocol/MessageHandler.java`

**Step 1: Add case branches to the switch statement**

In `handleMessage()`, after the `case "deactivate_stage"` line (line ~86), add:

```java
            case "get_stage" -> handleGetStage(message);
            case "update_stage" -> handleUpdateStage(message);
            case "set_stage_zone_config" -> handleSetStageZoneConfig(message);
            case "get_stage_templates" -> handleGetStageTemplates();
            case "scan_stage_blocks" -> handleScanStageBlocks(message);
```

**Step 2: Add `stageToJson()` and `stageZoneConfigToJson()` helper methods**

Add before the `createError()` method:

```java
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
```

**Step 3: Add `handleGetStage()` handler**

```java
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
```

**Step 4: Add `handleGetStageTemplates()` handler**

```java
    private JsonObject handleGetStageTemplates() {
        StageManager sm = mod.getStageManager();
        if (sm == null) return createError("Stage system not initialized");

        JsonObject response = new JsonObject();
        response.addProperty("type", "stage_templates");

        JsonArray templatesArray = new JsonArray();
        for (var entry : sm.getAllTemplates().entrySet()) {
            com.audioviz.stages.StageTemplate template = entry.getValue();
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
```

**Step 5: Add import for StageTemplate**

Add to imports at top of file:
```java
import com.audioviz.stages.StageTemplate;
```

**Step 6: Verify it compiles**

Run: `cd minecraft_mod && ./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 7: Commit**

```bash
git add minecraft_mod/src/main/java/com/audioviz/protocol/MessageHandler.java
git commit -m "feat(mod): add get_stage and get_stage_templates message handlers"
```

---

### Task 4: Add update_stage and set_stage_zone_config handlers

**Files:**
- Modify: `minecraft_mod/src/main/java/com/audioviz/protocol/MessageHandler.java`

**Step 1: Add `handleUpdateStage()` handler**

```java
    private JsonObject handleUpdateStage(JsonObject message) {
        if (!message.has("name")) return createError("Missing required field: name");
        String name = message.get("name").getAsString();
        StageManager sm = mod.getStageManager();
        if (sm == null) return createError("Stage system not initialized");
        Stage stage = sm.getStage(name);
        if (stage == null) return createError("Stage not found: " + name);

        // Move anchor if provided
        if (message.has("anchor")) {
            JsonObject anchorJson = message.getAsJsonObject("anchor");
            String worldName = anchorJson.has("world") ? anchorJson.get("world").getAsString() : stage.getWorldName();
            int x = anchorJson.has("x") ? anchorJson.get("x").getAsInt() : stage.getAnchor().getX();
            int y = anchorJson.has("y") ? anchorJson.get("y").getAsInt() : stage.getAnchor().getY();
            int z = anchorJson.has("z") ? anchorJson.get("z").getAsInt() : stage.getAnchor().getZ();
            sm.moveStage(stage, new BlockPos(x, y, z), worldName);
        }

        // Rotate if provided
        if (message.has("rotation")) {
            float rotation = message.get("rotation").getAsFloat();
            sm.rotateStage(stage, rotation);
        }

        // Add role if provided
        if (message.has("add_role")) {
            String roleName = message.get("add_role").getAsString();
            try {
                StageZoneRole role = StageZoneRole.valueOf(roleName.toUpperCase());
                sm.addRoleToStage(stage, role);
            } catch (IllegalArgumentException e) {
                return createError("Unknown zone role: " + roleName);
            }
        }

        // Remove role if provided
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
```

**Step 2: Add `handleSetStageZoneConfig()` handler**

```java
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

        JsonObject response = new JsonObject();
        response.addProperty("type", "stage_zone_config_updated");
        response.addProperty("stage", stageName);
        response.addProperty("role", role.name());
        response.add("config", stageZoneConfigToJson(config));
        return response;
    }
```

**Step 3: Verify it compiles**

Run: `cd minecraft_mod && ./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add minecraft_mod/src/main/java/com/audioviz/protocol/MessageHandler.java
git commit -m "feat(mod): add update_stage and set_stage_zone_config message handlers"
```

---

### Task 5: Add scan_stage_blocks handler

**Files:**
- Modify: `minecraft_mod/src/main/java/com/audioviz/protocol/MessageHandler.java`

**Step 1: Add imports for Fabric block/registry APIs**

Add to imports at top of file:
```java
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import org.joml.Vector3f;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
```

(Some of these may already be imported — the executing agent should check and skip duplicates.)

**Step 2: Add `handleScanStageBlocks()` and `scanStageBlocksSync()` methods**

```java
    /**
     * Scan all non-air blocks in the bounding box around a stage's zones.
     * Returns palette-compressed block data for 3D preview rendering.
     *
     * Block access requires the server thread, so the scan is submitted via
     * server.execute() and this method blocks on the CompletableFuture (up to 15s).
     */
    private JsonObject handleScanStageBlocks(JsonObject message) {
        if (!message.has("stage")) return createError("Missing required field: stage");
        String stageName = message.get("stage").getAsString();
        if (!isValidZoneName(stageName)) return createError("Invalid stage name");

        StageManager sm = mod.getStageManager();
        if (sm == null) return createError("Stage system not initialized");
        Stage stage = sm.getStage(stageName);
        if (stage == null) return createError("Stage not found: " + stageName);

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        mod.getServer().execute(() -> {
            try {
                future.complete(scanStageBlocksSync(stage, stageName));
            } catch (Exception e) {
                future.complete(createError("Scan failed: " + e.getMessage()));
            }
        });

        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            LOGGER.warn("Stage block scan timed out for: {}", stageName);
            return createError("Stage block scan timed out");
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
```

**Step 3: Verify it compiles**

Run: `cd minecraft_mod && ./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add minecraft_mod/src/main/java/com/audioviz/protocol/MessageHandler.java
git commit -m "feat(mod): add scan_stage_blocks handler for 3D preview world scanning"
```

---

### Task 6: Final build verification

**Step 1: Full build**

Run: `cd minecraft_mod && ./gradlew build 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

**Step 2: Commit the plan and design docs**

```bash
git add docs/plans/2026-02-27-fabric-stage-parity-design.md docs/plans/2026-02-27-fabric-stage-parity.md
git commit -m "docs: add fabric stage handler parity design and implementation plan"
```
