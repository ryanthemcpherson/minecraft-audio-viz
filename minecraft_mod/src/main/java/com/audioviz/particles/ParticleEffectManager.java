package com.audioviz.particles;

import com.audioviz.particles.impl.*;
import com.audioviz.patterns.AudioState;
import com.audioviz.zones.VisualizationZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages audio-reactive particle effects.
 * Handles effect registration, zone configurations, and particle spawning.
 *
 * <p>When entity positions are available (from batch_update), effects can
 * optionally spawn particles at entity centers — making particles a
 * straight swap for block display entities.
 *
 * <p>Ported from Paper: AudioVizPlugin → direct zone passing,
 * Bukkit scheduler → caller-driven (called from AudioVizMod tick),
 * Bedrock support removed (not applicable on Fabric).
 */
public class ParticleEffectManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz");

    /** All registered effects by ID. */
    private final Map<String, ParticleEffect> effects = new LinkedHashMap<>();

    /** Enabled effects per zone (zone name -> set of effect IDs). */
    private final Map<String, Set<String>> zoneEffects = new ConcurrentHashMap<>();

    /** Per-effect intensity settings (effect ID -> intensity 0-2). */
    private final Map<String, Double> effectIntensities = new ConcurrentHashMap<>();

    /** Global configuration. */
    private final ParticleEffectConfig globalConfig = new ParticleEffectConfig();

    /** Cooldown tracking for beat effects (effect ID -> last trigger time). */
    private final Map<String, Long> lastBeatTrigger = new ConcurrentHashMap<>();

    /** Cached entity positions per zone (zone name -> list of {x,y,z} in 0-1 space). */
    private final Map<String, List<double[]>> entityPositionCache = new ConcurrentHashMap<>();

    /** Particle count this tick (for limiting). */
    private int particlesThisTick = 0;

    public ParticleEffectManager() {
        registerDefaultEffects();
    }

    private void registerDefaultEffects() {
        registerEffect(new BassFlameEffect());
        registerEffect(new BeatRingEffect());
        registerEffect(new SoulFireEffect());
        registerEffect(new HighFreqNoteEffect());
        registerEffect(new SpectrumDustEffect());
        registerEffect(new AmbientMistEffect());
        LOGGER.info("Registered {} particle effects", effects.size());
    }

    public void registerEffect(ParticleEffect effect) {
        effects.put(effect.getId(), effect);
        effectIntensities.put(effect.getId(), 1.0);
    }

    public Collection<ParticleEffect> getAllEffects() { return effects.values(); }
    public ParticleEffect getEffect(String id) { return effects.get(id); }

    // ========== Zone Effect Management ==========

    public void enableEffect(String zoneName, String effectId) {
        zoneEffects.computeIfAbsent(zoneName, k -> ConcurrentHashMap.newKeySet()).add(effectId);
    }

    public void disableEffect(String zoneName, String effectId) {
        Set<String> enabled = zoneEffects.get(zoneName);
        if (enabled != null) enabled.remove(effectId);
    }

    public boolean toggleEffect(String zoneName, String effectId) {
        Set<String> enabled = zoneEffects.computeIfAbsent(zoneName, k -> ConcurrentHashMap.newKeySet());
        if (enabled.contains(effectId)) { enabled.remove(effectId); return false; }
        else { enabled.add(effectId); return true; }
    }

    public boolean isEffectEnabled(String zoneName, String effectId) {
        Set<String> enabled = zoneEffects.get(zoneName);
        return enabled != null && enabled.contains(effectId);
    }

    public Set<String> getEnabledEffects(String zoneName) {
        return zoneEffects.getOrDefault(zoneName, Collections.emptySet());
    }

    public void enableDefaultEffects(String zoneName) {
        enableEffect(zoneName, "bass_flame");
        enableEffect(zoneName, "beat_ring");
        enableEffect(zoneName, "high_notes");
    }

    // ========== Intensity ==========

    public void setEffectIntensity(String effectId, double intensity) {
        effectIntensities.put(effectId, Math.max(0, Math.min(2.0, intensity)));
    }

    public double getEffectIntensity(String effectId) {
        return effectIntensities.getOrDefault(effectId, 1.0);
    }

    public void setGlobalIntensity(double intensity) { globalConfig.setIntensity(intensity); }
    public double getGlobalIntensity() { return globalConfig.getIntensity(); }
    public void setMaxParticlesPerTick(int max) { globalConfig.setMaxParticlesPerTick(max); }

    // ========== Entity Position Cache ==========

    /**
     * Update cached entity positions for a zone.
     * Called from batch_update handler with normalized (0-1) positions.
     *
     * @param zoneName zone identifier
     * @param positions list of {x, y, z} arrays in 0-1 normalized space
     */
    public void updateEntityPositions(String zoneName, List<double[]> positions) {
        entityPositionCache.put(zoneName.toLowerCase(), positions);
    }

    /**
     * Clear entity position cache for a zone.
     */
    public void clearEntityPositions(String zoneName) {
        entityPositionCache.remove(zoneName.toLowerCase());
    }

    // ========== Audio Processing ==========

    /**
     * Process an audio update: calculate and spawn particles for a zone.
     * Called from the server tick handler.
     *
     * @param zone  the visualization zone (for local-to-world conversion)
     * @param audio current audio state
     */
    public void processAudioUpdate(VisualizationZone zone, AudioState audio) {
        if (!globalConfig.isEnabled()) return;
        String zoneName = zone.getName().toLowerCase();
        Set<String> enabled = zoneEffects.get(zoneName);
        if (enabled == null || enabled.isEmpty()) return;

        particlesThisTick = 0;
        List<ParticleSpawn> allSpawns = new ArrayList<>();

        for (String effectId : enabled) {
            ParticleEffect effect = effects.get(effectId);
            if (effect == null) continue;
            if (effect.isTriggeredByBeat() && !canTriggerBeatEffect(effectId)) continue;

            ParticleEffectConfig config = new ParticleEffectConfig(globalConfig.getIntensity());
            config.setEffectIntensity(getEffectIntensity(effectId));
            config.setBeatThreshold(globalConfig.getBeatThreshold());

            // Inject entity positions so effects can spawn at entity centers
            List<double[]> positions = entityPositionCache.get(zoneName);
            if (positions != null) {
                config.setEntityPositions(positions);
            }

            List<ParticleSpawn> spawns = effect.calculate(audio, config);
            if (effect.isTriggeredByBeat() && !spawns.isEmpty()) {
                lastBeatTrigger.put(effectId, System.currentTimeMillis());
            }
            allSpawns.addAll(spawns);
        }

        if (!allSpawns.isEmpty()) {
            spawnParticles(zone, allSpawns);
        }
    }

    private boolean canTriggerBeatEffect(String effectId) {
        Long lastTrigger = lastBeatTrigger.get(effectId);
        if (lastTrigger == null) return true;
        return System.currentTimeMillis() - lastTrigger >= ParticleEffectConfig.MIN_BEAT_COOLDOWN_MS;
    }

    private void spawnParticles(VisualizationZone zone, List<ParticleSpawn> spawns) {
        int limit = globalConfig.getMaxParticlesPerTick();
        List<ParticleSpawn> limited = spawns.size() <= limit ? spawns : spawns.subList(0, limit);
        ParticleSpawner.spawnAll(limited, zone);
    }

    // ========== Status ==========

    public Map<String, Object> getEffectStatus(String zoneName) {
        Map<String, Object> status = new HashMap<>();
        List<Map<String, Object>> effectList = new ArrayList<>();
        Set<String> enabled = getEnabledEffects(zoneName);
        for (ParticleEffect effect : effects.values()) {
            Map<String, Object> info = new HashMap<>();
            info.put("id", effect.getId());
            info.put("name", effect.getName());
            info.put("category", effect.getCategory());
            info.put("enabled", enabled.contains(effect.getId()));
            info.put("intensity", getEffectIntensity(effect.getId()));
            effectList.add(info);
        }
        status.put("effects", effectList);
        status.put("global_intensity", globalConfig.getIntensity());
        status.put("max_particles_per_tick", globalConfig.getMaxParticlesPerTick());
        return status;
    }

    public ParticleEffectConfig getGlobalConfig() { return globalConfig; }
    public void setEnabled(boolean enabled) { globalConfig.setEnabled(enabled); }
    public boolean isEnabled() { return globalConfig.isEnabled(); }
}
