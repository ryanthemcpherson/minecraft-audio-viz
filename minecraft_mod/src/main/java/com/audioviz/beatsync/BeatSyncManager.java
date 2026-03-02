package com.audioviz.beatsync;

import com.audioviz.AudioVizMod;
import com.audioviz.patterns.AudioState;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages per-zone BeatSyncConfig, persistence, and audio state modification.
 * Fabric port: uses Gson instead of Bukkit YamlConfiguration.
 */
public class BeatSyncManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path configDir;
    private final Map<String, BeatSyncConfig> configs = new LinkedHashMap<>();
    private final BeatSyncConfig globalConfig = new BeatSyncConfig();

    public BeatSyncManager(Path configDir) {
        this.configDir = configDir;
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
        Path file = configDir.resolve("beatsync.json");
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file);
            BeatSyncData data = GSON.fromJson(json, BeatSyncData.class);
            if (data != null && data.global != null) {
                globalConfig.setManualBpm(data.global.manualBpm);
                globalConfig.setPhaseOffset(data.global.phaseOffset);
                globalConfig.setBeatThresholdMultiplier(data.global.sensitivity);
                globalConfig.setProjectionEnabled(data.global.projectionEnabled);
            }
            LOGGER.info("Loaded beat sync config");
        } catch (IOException e) {
            LOGGER.error("Failed to load beatsync.json", e);
        }
    }

    public void save() {
        try {
            Files.createDirectories(configDir);
            BeatSyncData data = new BeatSyncData();
            data.global = new BeatSyncData.GlobalConfig();
            data.global.manualBpm = globalConfig.getManualBpm();
            data.global.phaseOffset = globalConfig.getPhaseOffset();
            data.global.sensitivity = globalConfig.getBeatThresholdMultiplier();
            data.global.projectionEnabled = globalConfig.isProjectionEnabled();
            Files.writeString(configDir.resolve("beatsync.json"), GSON.toJson(data));
        } catch (IOException e) {
            LOGGER.error("Failed to save beatsync.json", e);
        }
    }

    /** JSON serialization model for beatsync config. */
    private static class BeatSyncData {
        GlobalConfig global;
        static class GlobalConfig {
            double manualBpm = 0;
            double phaseOffset = 0;
            double sensitivity = 1.0;
            boolean projectionEnabled = true;
        }
    }
}
