package com.audioviz.stages;

import com.audioviz.AudioVizPlugin;
import com.audioviz.decorators.DecoratorConfig;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages stage lifecycle: creation from templates, persistence, zone orchestration.
 * Stages are collections of zones with semantic roles, created from templates.
 */
public class StageManager {

    private final AudioVizPlugin plugin;
    private final Map<String, Stage> stages;
    private final Map<String, StageTemplate> customTemplates;
    private final File stagesFile;

    public StageManager(AudioVizPlugin plugin) {
        this.plugin = plugin;
        this.stages = new ConcurrentHashMap<>();
        this.customTemplates = new LinkedHashMap<>();
        this.stagesFile = new File(plugin.getDataFolder(), "stages.yml");
    }

    // ========== Stage CRUD ==========

    /**
     * Create a new stage from a template at the given anchor location.
     * Creates all underlying zones via ZoneManager.
     *
     * @return the created Stage, or null if creation failed
     */
    public Stage createStage(String name, Location anchor, String templateName) {
        if (stages.containsKey(name.toLowerCase())) {
            return null; // Already exists
        }

        StageTemplate template = getTemplate(templateName);
        if (template == null) {
            return null; // Unknown template
        }

        Stage stage = new Stage(name, anchor, templateName);

        // Create zones for each role in the template
        for (StageZoneRole role : template.getRoles()) {
            String zoneName = name.toLowerCase() + "_" + role.name().toLowerCase();

            // Calculate zone position
            Location zoneOrigin = stage.getWorldLocationForRole(role);

            // Get effective size
            Vector size = template.getSizeForRole(role);

            // Create zone via ZoneManager
            VisualizationZone zone = plugin.getZoneManager().createZone(zoneName, zoneOrigin);
            if (zone != null) {
                zone.setSize(size);
                zone.setRotation(stage.getRotation());

                // Register role -> zone mapping
                stage.getRoleToZone().put(role, zoneName);

                // Set config for this role (from template or default)
                StageZoneConfig config = template.getDefaultConfigs().containsKey(role)
                    ? new StageZoneConfig(template.getDefaultConfigs().get(role))
                    : new StageZoneConfig();
                config.setPattern(role.getSuggestedPattern());

                // Use template config's pattern if one was set
                StageZoneConfig templateConfig = template.getDefaultConfigs().get(role);
                if (templateConfig != null) {
                    config.setPattern(templateConfig.getPattern());
                    config.setEntityCount(templateConfig.getEntityCount());
                }

                stage.getZoneConfigs().put(role, config);
            } else {
                plugin.getLogger().warning("Failed to create zone '" + zoneName + "' for stage '" + name + "'");
            }
        }

        plugin.getZoneManager().saveZones();
        stages.put(name.toLowerCase(), stage);
        saveStages();

        plugin.getLogger().info("Created stage '" + name + "' from template '" + templateName +
            "' with " + stage.getRoleToZone().size() + " zones");

        return stage;
    }

    /**
     * Delete a stage and all its zones.
     */
    public boolean deleteStage(String name) {
        Stage stage = stages.remove(name.toLowerCase());
        if (stage == null) {
            return false;
        }

        // Deactivate decorators before deleting zones
        if (plugin.getDecoratorManager() != null) {
            plugin.getDecoratorManager().deactivateDecorators(stage);
        }

        // Delete all zones owned by this stage
        for (String zoneName : stage.getZoneNames()) {
            plugin.getEntityPoolManager().cleanupZone(zoneName);
            plugin.getZoneManager().deleteZone(zoneName);
        }

        saveStages();
        plugin.getLogger().info("Deleted stage '" + name + "'");
        return true;
    }

    /**
     * Activate a stage: initialize entity pools for all zones.
     */
    public void activateStage(Stage stage) {
        for (Map.Entry<StageZoneRole, String> entry : stage.getRoleToZone().entrySet()) {
            StageZoneRole role = entry.getKey();
            String zoneName = entry.getValue();
            StageZoneConfig config = stage.getZoneConfigs().getOrDefault(role, new StageZoneConfig());

            Material material = Material.matchMaterial(config.getBlockType());
            if (material == null) material = Material.SEA_LANTERN;

            plugin.getEntityPoolManager().initializeBlockPool(zoneName, config.getEntityCount(), material);
        }

        stage.setActive(true);
        stage.setLastActivatedAt(System.currentTimeMillis());
        saveStages();

        // Activate decorators for this stage
        if (plugin.getDecoratorManager() != null) {
            plugin.getDecoratorManager().activateDecorators(stage);
        }

        plugin.getLogger().info("Activated stage '" + stage.getName() + "'");
    }

    /**
     * Deactivate a stage: clean up all entity pools.
     */
    public void deactivateStage(Stage stage) {
        // Deactivate decorators first
        if (plugin.getDecoratorManager() != null) {
            plugin.getDecoratorManager().deactivateDecorators(stage);
        }

        for (String zoneName : stage.getZoneNames()) {
            plugin.getEntityPoolManager().cleanupZone(zoneName);
        }

        stage.setActive(false);
        saveStages();
        plugin.getLogger().info("Deactivated stage '" + stage.getName() + "'");
    }

    /**
     * Move the stage anchor and reposition all zones.
     */
    public void moveStage(Stage stage, Location newAnchor) {
        stage.setAnchor(newAnchor);

        for (Map.Entry<StageZoneRole, String> entry : stage.getRoleToZone().entrySet()) {
            StageZoneRole role = entry.getKey();
            String zoneName = entry.getValue();
            VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
            if (zone != null) {
                zone.setOrigin(stage.getWorldLocationForRole(role));
            }
        }

        plugin.getZoneManager().saveZones();
        saveStages();
    }

    /**
     * Rotate the entire stage and recalculate zone positions.
     */
    public void rotateStage(Stage stage, float newRotation) {
        stage.setRotation(newRotation);

        for (Map.Entry<StageZoneRole, String> entry : stage.getRoleToZone().entrySet()) {
            StageZoneRole role = entry.getKey();
            String zoneName = entry.getValue();
            VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
            if (zone != null) {
                zone.setOrigin(stage.getWorldLocationForRole(role));
                zone.setRotation(newRotation);
            }
        }

        plugin.getZoneManager().saveZones();
        saveStages();
    }

    /**
     * Add a zone role to an existing stage.
     */
    public boolean addRoleToStage(Stage stage, StageZoneRole role) {
        if (stage.getRoleToZone().containsKey(role)) {
            return false; // Already has this role
        }

        String zoneName = stage.getName().toLowerCase() + "_" + role.name().toLowerCase();
        Location zoneOrigin = stage.getWorldLocationForRole(role);

        // Get size from template or default
        StageTemplate template = getTemplate(stage.getTemplateName());
        Vector size = (template != null) ? template.getSizeForRole(role) : role.getDefaultSize();

        VisualizationZone zone = plugin.getZoneManager().createZone(zoneName, zoneOrigin);
        if (zone == null) return false;

        zone.setSize(size);
        zone.setRotation(stage.getRotation());

        stage.getRoleToZone().put(role, zoneName);

        StageZoneConfig config = new StageZoneConfig();
        config.setPattern(role.getSuggestedPattern());
        stage.getZoneConfigs().put(role, config);

        plugin.getZoneManager().saveZones();
        saveStages();
        return true;
    }

    /**
     * Remove a zone role from a stage.
     */
    public boolean removeRoleFromStage(Stage stage, StageZoneRole role) {
        String zoneName = stage.getRoleToZone().remove(role);
        if (zoneName == null) return false;

        stage.getZoneConfigs().remove(role);

        plugin.getEntityPoolManager().cleanupZone(zoneName);
        plugin.getZoneManager().deleteZone(zoneName);

        saveStages();
        return true;
    }

    // ========== Lookup ==========

    public Stage getStage(String name) {
        return stages.get(name.toLowerCase());
    }

    public Collection<Stage> getAllStages() {
        return Collections.unmodifiableCollection(stages.values());
    }

    public int getStageCount() {
        return stages.size();
    }

    public boolean stageExists(String name) {
        return stages.containsKey(name.toLowerCase());
    }

    public Set<String> getStageNames() {
        return Collections.unmodifiableSet(stages.keySet());
    }

    /**
     * Find the stage that owns a zone.
     */
    public Stage findStageForZone(String zoneName) {
        for (Stage stage : stages.values()) {
            if (stage.ownsZone(zoneName)) {
                return stage;
            }
        }
        return null;
    }

    // ========== Bulk Operations ==========

    /**
     * Activate multiple stages at once.
     * @return number of stages activated
     */
    public int bulkActivate(Collection<String> stageNames) {
        int count = 0;
        for (String name : stageNames) {
            Stage stage = stages.get(name.toLowerCase());
            if (stage != null && !stage.isActive()) {
                activateStage(stage);
                count++;
            }
        }
        return count;
    }

    /**
     * Deactivate multiple stages at once.
     * @return number of stages deactivated
     */
    public int bulkDeactivate(Collection<String> stageNames) {
        int count = 0;
        for (String name : stageNames) {
            Stage stage = stages.get(name.toLowerCase());
            if (stage != null && stage.isActive()) {
                deactivateStage(stage);
                count++;
            }
        }
        return count;
    }

    /**
     * Get all unique tags currently in use across stages.
     */
    public Set<String> getAllTags() {
        Set<String> tags = new TreeSet<>();
        for (Stage stage : stages.values()) {
            if (stage.getTag() != null && !stage.getTag().isEmpty()) {
                tags.add(stage.getTag());
            }
        }
        return tags;
    }

    // ========== Templates ==========

    /**
     * Get a template by name (checks built-in first, then custom).
     */
    public StageTemplate getTemplate(String name) {
        StageTemplate builtin = StageTemplate.getBuiltin(name);
        if (builtin != null) return builtin;
        return customTemplates.get(name.toLowerCase());
    }

    /**
     * Get all available template names.
     */
    public List<String> getTemplateNames() {
        List<String> names = new ArrayList<>(StageTemplate.getBuiltinTemplates().keySet());
        names.addAll(customTemplates.keySet());
        return names;
    }

    /**
     * Get all templates (built-in + custom).
     */
    public Map<String, StageTemplate> getAllTemplates() {
        Map<String, StageTemplate> all = new LinkedHashMap<>(StageTemplate.getBuiltinTemplates());
        all.putAll(customTemplates);
        return Collections.unmodifiableMap(all);
    }

    // ========== Persistence ==========

    public void loadStages() {
        if (!stagesFile.exists()) {
            plugin.getLogger().info("No stages file found, starting fresh.");
            return;
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(stagesFile);
        ConfigurationSection stagesSection = config.getConfigurationSection("stages");

        if (stagesSection == null) return;

        for (String stageName : stagesSection.getKeys(false)) {
            ConfigurationSection stageData = stagesSection.getConfigurationSection(stageName);
            if (stageData == null) continue;

            try {
                UUID id = UUID.fromString(stageData.getString("id", UUID.randomUUID().toString()));
                String templateName = stageData.getString("template", "custom");
                boolean active = stageData.getBoolean("active", false);
                float rotation = (float) stageData.getDouble("rotation", 0);

                // Load anchor
                String worldName = stageData.getString("anchor.world", "world");
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    plugin.getLogger().warning("World '" + worldName + "' not found for stage '" + stageName + "'");
                    continue;
                }
                Location anchor = new Location(world,
                    stageData.getDouble("anchor.x", 0),
                    stageData.getDouble("anchor.y", 64),
                    stageData.getDouble("anchor.z", 0));

                // Load zone mappings
                Map<StageZoneRole, String> roleToZone = new EnumMap<>(StageZoneRole.class);
                Map<StageZoneRole, StageZoneConfig> zoneConfigs = new EnumMap<>(StageZoneRole.class);

                ConfigurationSection zonesSection = stageData.getConfigurationSection("zones");
                if (zonesSection != null) {
                    for (String roleKey : zonesSection.getKeys(false)) {
                        try {
                            StageZoneRole role = StageZoneRole.valueOf(roleKey.toUpperCase());
                            ConfigurationSection zoneData = zonesSection.getConfigurationSection(roleKey);
                            if (zoneData == null) continue;

                            String zoneName = zoneData.getString("zone_name");
                            if (zoneName != null) {
                                roleToZone.put(role, zoneName);
                            }

                            // Load zone config
                            ConfigurationSection configData = zoneData.getConfigurationSection("config");
                            if (configData != null) {
                                StageZoneConfig zoneConfig = new StageZoneConfig();
                                zoneConfig.setPattern(configData.getString("pattern", "spectrum"));
                                zoneConfig.setEntityCount(configData.getInt("entity_count", 16));
                                zoneConfig.setRenderMode(configData.getString("render_mode", "entities"));
                                zoneConfig.setBlockType(configData.getString("block_type", "SEA_LANTERN"));
                                zoneConfig.setBrightness(configData.getInt("brightness", 15));
                                zoneConfig.setGlowOnBeat(configData.getBoolean("glow_on_beat", false));
                                zoneConfig.setIntensityMultiplier((float) configData.getDouble("intensity_multiplier", 1.0));
                                zoneConfigs.put(role, zoneConfig);
                            }
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("Unknown zone role '" + roleKey + "' in stage '" + stageName + "'");
                        }
                    }
                }

                // Load decorator configurations (backward compatible)
                Map<String, DecoratorConfig> decoratorConfigs = new LinkedHashMap<>();
                ConfigurationSection decoSection = stageData.getConfigurationSection("decorators");
                if (decoSection != null) {
                    for (String decoId : decoSection.getKeys(false)) {
                        ConfigurationSection decoData = decoSection.getConfigurationSection(decoId);
                        if (decoData == null) continue;

                        boolean decoEnabled = decoData.getBoolean("enabled", true);
                        Map<String, Object> decoSettings = new LinkedHashMap<>();
                        ConfigurationSection settingsSection = decoData.getConfigurationSection("settings");
                        if (settingsSection != null) {
                            for (String settingKey : settingsSection.getKeys(false)) {
                                decoSettings.put(settingKey, settingsSection.get(settingKey));
                            }
                        }
                        decoratorConfigs.put(decoId, new DecoratorConfig(decoEnabled, decoSettings));
                    }
                }

                // Organization metadata (backward compatible - defaults for old files)
                String tag = stageData.getString("tag", "");
                boolean pinned = stageData.getBoolean("pinned", false);
                long createdAt = stageData.getLong("created_at", System.currentTimeMillis());
                long lastActivatedAt = stageData.getLong("last_activated_at", 0L);

                Stage stage = new Stage(stageName, id, anchor, rotation, templateName, active,
                    roleToZone, zoneConfigs, decoratorConfigs,
                    tag, pinned, createdAt, lastActivatedAt);
                stages.put(stageName.toLowerCase(), stage);

                plugin.getLogger().info("Loaded stage: " + stage);

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load stage '" + stageName + "'", e);
            }
        }

        plugin.getLogger().info("Loaded " + stages.size() + " stage(s)");
    }

    public void saveStages() {
        FileConfiguration config = new YamlConfiguration();

        for (Stage stage : stages.values()) {
            String path = "stages." + stage.getName();

            config.set(path + ".id", stage.getId().toString());
            config.set(path + ".template", stage.getTemplateName());
            config.set(path + ".active", stage.isActive());
            config.set(path + ".rotation", stage.getRotation());

            // Organization metadata
            config.set(path + ".tag", stage.getTag());
            config.set(path + ".pinned", stage.isPinned());
            config.set(path + ".created_at", stage.getCreatedAt());
            config.set(path + ".last_activated_at", stage.getLastActivatedAt());

            // Anchor
            Location anchor = stage.getAnchor();
            config.set(path + ".anchor.world", anchor.getWorld().getName());
            config.set(path + ".anchor.x", anchor.getX());
            config.set(path + ".anchor.y", anchor.getY());
            config.set(path + ".anchor.z", anchor.getZ());

            // Zones
            for (Map.Entry<StageZoneRole, String> entry : stage.getRoleToZone().entrySet()) {
                StageZoneRole role = entry.getKey();
                String zoneName = entry.getValue();
                String zonePath = path + ".zones." + role.name();

                config.set(zonePath + ".zone_name", zoneName);

                StageZoneConfig zoneConfig = stage.getZoneConfigs().get(role);
                if (zoneConfig != null) {
                    config.set(zonePath + ".config.pattern", zoneConfig.getPattern());
                    config.set(zonePath + ".config.entity_count", zoneConfig.getEntityCount());
                    config.set(zonePath + ".config.render_mode", zoneConfig.getRenderMode());
                    config.set(zonePath + ".config.block_type", zoneConfig.getBlockType());
                    config.set(zonePath + ".config.brightness", zoneConfig.getBrightness());
                    config.set(zonePath + ".config.glow_on_beat", zoneConfig.isGlowOnBeat());
                    config.set(zonePath + ".config.intensity_multiplier", zoneConfig.getIntensityMultiplier());
                }
            }

            // Decorator configs
            Map<String, DecoratorConfig> decoratorConfigs = stage.getDecoratorConfigs();
            if (!decoratorConfigs.isEmpty()) {
                for (Map.Entry<String, DecoratorConfig> decoEntry : decoratorConfigs.entrySet()) {
                    String decoPath = path + ".decorators." + decoEntry.getKey();
                    DecoratorConfig decoConfig = decoEntry.getValue();
                    config.set(decoPath + ".enabled", decoConfig.isEnabled());
                    for (Map.Entry<String, Object> setting : decoConfig.getSettings().entrySet()) {
                        config.set(decoPath + ".settings." + setting.getKey(), setting.getValue());
                    }
                }
            }
        }

        try {
            config.save(stagesFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save stages: " + e.getMessage());
        }
    }
}
