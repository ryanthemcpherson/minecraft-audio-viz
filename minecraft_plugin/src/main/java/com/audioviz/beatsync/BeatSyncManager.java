package com.audioviz.beatsync;

import com.audioviz.AudioVizPlugin;
import com.audioviz.patterns.AudioState;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Manages per-zone BeatSyncConfig, persistence, and audio state modification.
 */
public class BeatSyncManager {

    private final AudioVizPlugin plugin;
    private final Map<String, BeatSyncConfig> configs = new LinkedHashMap<>();
    private final BeatSyncConfig globalConfig = new BeatSyncConfig();
    private final File configFile;

    public BeatSyncManager(AudioVizPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "beatsync.yml");
    }

    public BeatSyncConfig getGlobalConfig() { return globalConfig; }

    public BeatSyncConfig getConfig(String zoneName) {
        return configs.getOrDefault(zoneName, globalConfig);
    }

    /**
     * Apply beat sync overrides to an AudioState.
     * Returns a new AudioState with modified phase if overrides are active.
     */
    public AudioState applyOverrides(AudioState audio) {
        double phaseOffset = globalConfig.getPhaseOffset();

        // If no overrides active, return as-is
        if (phaseOffset == 0.0) return audio;

        double adjustedPhase = BeatSyncConfig.applyPhaseOffset(audio.getBeatPhase(), phaseOffset);

        return new AudioState(
            audio.getBands(),
            audio.getAmplitude(),
            audio.isBeat(),
            audio.getBeatIntensity(),
            audio.getTempoConfidence(),
            adjustedPhase,
            audio.getFrame()
        );
    }

    // ========== Persistence ==========

    public void load() {
        if (!configFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(configFile);

        if (yml.contains("global")) {
            var section = yml.getConfigurationSection("global");
            if (section != null) {
                globalConfig.setManualBpm(section.getDouble("manual_bpm", 0));
                globalConfig.setPhaseOffset(section.getDouble("phase_offset", 0));
                globalConfig.setBeatThresholdMultiplier(section.getDouble("sensitivity", 1.0));
                globalConfig.setProjectionEnabled(section.getBoolean("projection_enabled", true));
            }
        }
        plugin.getLogger().info("Loaded beat sync config");
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        var section = yml.createSection("global");
        section.set("manual_bpm", globalConfig.getManualBpm());
        section.set("phase_offset", globalConfig.getPhaseOffset());
        section.set("sensitivity", globalConfig.getBeatThresholdMultiplier());
        section.set("projection_enabled", globalConfig.isProjectionEnabled());

        try {
            yml.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save beatsync.yml", e);
        }
    }
}
