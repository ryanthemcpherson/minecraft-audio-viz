package com.audioviz.particles;

import com.audioviz.AudioVizPlugin;
import com.audioviz.bedrock.BedrockSupport;
import com.audioviz.particles.impl.*;
import com.audioviz.patterns.AudioState;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Manages audio-reactive particle effects.
 * Handles effect registration, zone configurations, and particle spawning.
 */
public class ParticleEffectManager {

    private final AudioVizPlugin plugin;
    private final Logger logger;

    /** All registered effects by ID */
    private final Map<String, ParticleEffect> effects = new LinkedHashMap<>();

    /** Enabled effects per zone (zone name -> set of effect IDs) */
    private final Map<String, Set<String>> zoneEffects = new ConcurrentHashMap<>();

    /** Per-effect intensity settings (effect ID -> intensity 0-2) */
    private final Map<String, Double> effectIntensities = new ConcurrentHashMap<>();

    /** Global configuration */
    private final ParticleEffectConfig globalConfig = new ParticleEffectConfig();

    /** Cooldown tracking for beat effects (effect ID -> last trigger time) */
    private final Map<String, Long> lastBeatTrigger = new ConcurrentHashMap<>();

    /** Particle count this tick (for limiting) */
    private int particlesThisTick = 0;

    public ParticleEffectManager(AudioVizPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        registerDefaultEffects();
    }

    /**
     * Register all built-in particle effects.
     */
    private void registerDefaultEffects() {
        // Beat effects
        registerEffect(new BassFlameEffect());
        registerEffect(new BeatRingEffect());
        registerEffect(new SoulFireEffect());

        // Ambient effects
        registerEffect(new HighFreqNoteEffect());
        registerEffect(new SpectrumDustEffect());
        registerEffect(new AmbientMistEffect());

        logger.info("Registered " + effects.size() + " particle effects");
    }

    /**
     * Register a particle effect.
     */
    public void registerEffect(ParticleEffect effect) {
        effects.put(effect.getId(), effect);
        effectIntensities.put(effect.getId(), 1.0);
    }

    /**
     * Get all registered effects.
     */
    public Collection<ParticleEffect> getAllEffects() {
        return effects.values();
    }

    /**
     * Get effect by ID.
     */
    public ParticleEffect getEffect(String id) {
        return effects.get(id);
    }

    // ==================== Zone Effect Management ====================

    /**
     * Enable an effect for a zone.
     */
    public void enableEffect(String zoneName, String effectId) {
        zoneEffects.computeIfAbsent(zoneName, k -> ConcurrentHashMap.newKeySet())
                   .add(effectId);
        logger.info("Enabled particle effect '" + effectId + "' for zone '" + zoneName + "'");
    }

    /**
     * Disable an effect for a zone.
     */
    public void disableEffect(String zoneName, String effectId) {
        Set<String> enabled = zoneEffects.get(zoneName);
        if (enabled != null) {
            enabled.remove(effectId);
        }
        logger.info("Disabled particle effect '" + effectId + "' for zone '" + zoneName + "'");
    }

    /**
     * Toggle an effect for a zone.
     */
    public boolean toggleEffect(String zoneName, String effectId) {
        Set<String> enabled = zoneEffects.computeIfAbsent(zoneName, k -> ConcurrentHashMap.newKeySet());
        if (enabled.contains(effectId)) {
            enabled.remove(effectId);
            return false;
        } else {
            enabled.add(effectId);
            return true;
        }
    }

    /**
     * Check if an effect is enabled for a zone.
     */
    public boolean isEffectEnabled(String zoneName, String effectId) {
        Set<String> enabled = zoneEffects.get(zoneName);
        return enabled != null && enabled.contains(effectId);
    }

    /**
     * Get all enabled effects for a zone.
     */
    public Set<String> getEnabledEffects(String zoneName) {
        return zoneEffects.getOrDefault(zoneName, Collections.emptySet());
    }

    /**
     * Enable default effects for a zone.
     */
    public void enableDefaultEffects(String zoneName) {
        enableEffect(zoneName, "bass_flame");
        enableEffect(zoneName, "beat_ring");
        enableEffect(zoneName, "high_notes");
    }

    // ==================== Intensity Management ====================

    /**
     * Set intensity for a specific effect (0-2).
     */
    public void setEffectIntensity(String effectId, double intensity) {
        effectIntensities.put(effectId, Math.max(0, Math.min(2.0, intensity)));
    }

    /**
     * Get intensity for a specific effect.
     */
    public double getEffectIntensity(String effectId) {
        return effectIntensities.getOrDefault(effectId, 1.0);
    }

    /**
     * Set global intensity multiplier.
     */
    public void setGlobalIntensity(double intensity) {
        globalConfig.setIntensity(intensity);
    }

    /**
     * Get global intensity.
     */
    public double getGlobalIntensity() {
        return globalConfig.getIntensity();
    }

    /**
     * Set max particles per tick.
     */
    public void setMaxParticlesPerTick(int max) {
        globalConfig.setMaxParticlesPerTick(max);
    }

    // ==================== Particle Calculation & Spawning ====================

    /**
     * Calculate and spawn particles for a zone based on audio state.
     * Called from MessageHandler on each batch_update.
     */
    public void processAudioUpdate(String zoneName, AudioState audio) {
        if (!globalConfig.isEnabled()) return;

        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
        if (zone == null) return;

        Set<String> enabled = zoneEffects.get(zoneName);
        if (enabled == null || enabled.isEmpty()) return;

        // Reset particle counter for this tick
        particlesThisTick = 0;

        List<ParticleSpawn> allSpawns = new ArrayList<>();

        for (String effectId : enabled) {
            ParticleEffect effect = effects.get(effectId);
            if (effect == null) continue;

            // Check beat cooldown for beat-triggered effects
            if (effect.isTriggeredByBeat() && !canTriggerBeatEffect(effectId)) {
                continue;
            }

            // Create config with effect-specific intensity
            ParticleEffectConfig config = new ParticleEffectConfig(globalConfig.getIntensity());
            config.setEffectIntensity(getEffectIntensity(effectId));
            config.setBeatThreshold(globalConfig.getBeatThreshold());

            // Calculate particles
            List<ParticleSpawn> spawns = effect.calculate(audio, config);

            // Track beat trigger
            if (effect.isTriggeredByBeat() && !spawns.isEmpty()) {
                lastBeatTrigger.put(effectId, System.currentTimeMillis());
            }

            allSpawns.addAll(spawns);
        }

        // Spawn particles (on main thread)
        if (!allSpawns.isEmpty()) {
            spawnParticles(zone, allSpawns);
        }
    }

    /**
     * Check if a beat effect can trigger (cooldown elapsed).
     */
    private boolean canTriggerBeatEffect(String effectId) {
        Long lastTrigger = lastBeatTrigger.get(effectId);
        if (lastTrigger == null) return true;
        return System.currentTimeMillis() - lastTrigger >= ParticleEffectConfig.MIN_BEAT_COOLDOWN_MS;
    }

    /**
     * Spawn particles in a zone.
     * When Bedrock fallback is active, particles are sent per-player to Bedrock players only.
     */
    private void spawnParticles(VisualizationZone zone, List<ParticleSpawn> spawns) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            BedrockSupport bedrockSupport = plugin.getBedrockSupport();
            boolean bedrockTargeted = bedrockSupport != null
                && bedrockSupport.needsParticleFallback()
                && bedrockSupport.hasBedrockPlayersOnline();

            for (ParticleSpawn spawn : spawns) {
                // Check global limit
                if (particlesThisTick >= globalConfig.getMaxParticlesPerTick()) {
                    break;
                }

                // Convert local to world coordinates
                Location loc = zone.localToWorld(spawn.getX(), spawn.getY(), spawn.getZ());

                try {
                    if (bedrockTargeted) {
                        spawnForBedrockPlayers(bedrockSupport, loc, spawn);
                    } else if (spawn.getData() != null) {
                        zone.getWorld().spawnParticle(
                            spawn.getType(), loc,
                            spawn.getCount(),
                            spawn.getOffsetX(), spawn.getOffsetY(), spawn.getOffsetZ(),
                            spawn.getSpeed(),
                            spawn.getData()
                        );
                    } else {
                        zone.getWorld().spawnParticle(
                            spawn.getType(), loc,
                            spawn.getCount(),
                            spawn.getOffsetX(), spawn.getOffsetY(), spawn.getOffsetZ(),
                            spawn.getSpeed()
                        );
                    }
                    particlesThisTick += spawn.getCount();
                } catch (Exception e) {
                    logger.warning("Failed to spawn particle " + spawn.getType() + ": " + e.getMessage());
                }
            }
        });
    }

    /**
     * Send particle effect only to Bedrock players (per-player targeting).
     */
    private void spawnForBedrockPlayers(BedrockSupport bedrockSupport, Location loc, ParticleSpawn spawn) {
        int maxDist = bedrockSupport.getMaxRenderDistance();
        double maxDistSq = (double) maxDist * maxDist;

        for (UUID uuid : bedrockSupport.getBedrockPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;
            if (!player.getWorld().equals(loc.getWorld())) continue;
            if (player.getLocation().distanceSquared(loc) > maxDistSq) continue;

            if (spawn.getData() != null) {
                player.spawnParticle(
                    spawn.getType(), loc, spawn.getCount(),
                    spawn.getOffsetX(), spawn.getOffsetY(), spawn.getOffsetZ(),
                    spawn.getSpeed(), spawn.getData()
                );
            } else {
                player.spawnParticle(
                    spawn.getType(), loc, spawn.getCount(),
                    spawn.getOffsetX(), spawn.getOffsetY(), spawn.getOffsetZ(),
                    spawn.getSpeed()
                );
            }
        }
    }

    // ==================== Serialization for WebSocket ====================

    /**
     * Get effect status for WebSocket response.
     */
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

    // ==================== Configuration ====================

    public ParticleEffectConfig getGlobalConfig() {
        return globalConfig;
    }

    public void setEnabled(boolean enabled) {
        globalConfig.setEnabled(enabled);
    }

    public boolean isEnabled() {
        return globalConfig.isEnabled();
    }
}
