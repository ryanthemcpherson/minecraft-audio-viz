package com.audioviz.effects;

import com.audioviz.AudioVizPlugin;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages beat-triggered effects for visualization zones.
 * Handles effect registration, zone configurations, and beat event processing.
 */
public class BeatEventManager {

    private final AudioVizPlugin plugin;
    private final Map<String, BeatEffect> registeredEffects;
    private final Map<String, BeatEffectConfig> zoneConfigs;

    public BeatEventManager(AudioVizPlugin plugin) {
        this.plugin = plugin;
        this.registeredEffects = new ConcurrentHashMap<>();
        this.zoneConfigs = new ConcurrentHashMap<>();

        // Register built-in effects
        registerBuiltInEffects();
    }

    /**
     * Register built-in beat effects.
     */
    private void registerBuiltInEffects() {
        register(new ParticleBurstEffect());
        register(new ScreenShakeEffect());
        register(new LightningEffect());
        register(new ExplosionVisualEffect());
    }

    /**
     * Register a beat effect.
     */
    public void register(BeatEffect effect) {
        registeredEffects.put(effect.getId(), effect);
        plugin.getLogger().info("Registered beat effect: " + effect.getId());
    }

    /**
     * Get a registered effect by ID.
     */
    public BeatEffect get(String id) {
        return registeredEffects.get(id);
    }

    /**
     * Get all registered effects.
     */
    public Collection<BeatEffect> getAllEffects() {
        return Collections.unmodifiableCollection(registeredEffects.values());
    }

    /**
     * Set the effect configuration for a zone.
     */
    public void setZoneConfig(String zoneName, BeatEffectConfig config) {
        zoneConfigs.put(zoneName.toLowerCase(), config);
    }

    /**
     * Get the effect configuration for a zone.
     */
    public BeatEffectConfig getZoneConfig(String zoneName) {
        return zoneConfigs.get(zoneName.toLowerCase());
    }

    /**
     * Remove configuration for a zone.
     * Call this when a zone is deleted to prevent memory leak.
     */
    public void removeZoneConfig(String zoneName) {
        zoneConfigs.remove(zoneName.toLowerCase());
    }

    /**
     * Clear all zone configurations.
     * Called on plugin disable.
     */
    public void clearAllConfigs() {
        zoneConfigs.clear();
    }

    /**
     * Process a beat event for a zone.
     *
     * @param zoneName Zone name
     * @param beatType Type of beat detected
     * @param intensity Beat intensity (0.0 - 1.0)
     */
    public void processBeat(String zoneName, BeatType beatType, double intensity) {
        BeatEffectConfig config = zoneConfigs.get(zoneName.toLowerCase());
        if (config == null || !config.hasEffects()) {
            return;
        }

        // Check threshold
        if (intensity < config.getThreshold(beatType)) {
            return;
        }

        // Check cooldown
        if (!config.canTrigger(beatType)) {
            return;
        }

        // Get zone
        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
        if (zone == null) {
            return;
        }

        // Get viewers in range
        Collection<Player> viewers = getViewersNearZone(zone);
        if (viewers.isEmpty()) {
            return;
        }

        // Trigger effects
        Location center = zone.getCenter();
        for (BeatEffect effect : config.getEffects(beatType)) {
            try {
                effect.trigger(center, zone, intensity, viewers);
            } catch (Exception e) {
                plugin.getLogger().warning("Error triggering effect " + effect.getId() + ": " + e.getMessage());
            }
        }

        // Mark as triggered
        config.markTriggered(beatType);
    }

    /**
     * Get players near a zone (within render distance).
     */
    private Collection<Player> getViewersNearZone(VisualizationZone zone) {
        Location center = zone.getCenter();
        double maxDistance = Math.max(zone.getSize().length(), 64);
        List<Player> viewers = new ArrayList<>();

        for (Player player : zone.getWorld().getPlayers()) {
            if (player.getLocation().distance(center) <= maxDistance) {
                viewers.add(player);
            }
        }

        return viewers;
    }

    // ==================== Built-in Effects ====================

    /**
     * Particle burst effect on beats.
     */
    private static class ParticleBurstEffect implements BeatEffect {
        @Override
        public String getId() {
            return "particle_burst";
        }

        @Override
        public String getName() {
            return "Particle Burst";
        }

        @Override
        public void trigger(Location location, VisualizationZone zone, double intensity, Collection<Player> viewers) {
            int count = (int) (20 * intensity);
            double spread = 2.0 * intensity;

            for (Player player : viewers) {
                player.spawnParticle(
                    org.bukkit.Particle.END_ROD,
                    location,
                    count,
                    spread, spread, spread,
                    0.1
                );
            }
        }
    }

    /**
     * Screen shake effect using camera velocity.
     */
    private static class ScreenShakeEffect implements BeatEffect {
        @Override
        public String getId() {
            return "screen_shake";
        }

        @Override
        public String getName() {
            return "Screen Shake";
        }

        @Override
        public boolean isPerPlayer() {
            return true;
        }

        @Override
        public void trigger(Location location, VisualizationZone zone, double intensity, Collection<Player> viewers) {
            double shakeMagnitude = 0.1 * intensity;

            for (Player player : viewers) {
                // Apply small velocity nudge for shake effect
                org.bukkit.util.Vector velocity = player.getVelocity();
                double offsetX = (Math.random() - 0.5) * shakeMagnitude;
                double offsetY = (Math.random() - 0.5) * shakeMagnitude * 0.5;
                double offsetZ = (Math.random() - 0.5) * shakeMagnitude;
                player.setVelocity(velocity.add(new org.bukkit.util.Vector(offsetX, offsetY, offsetZ)));
            }
        }
    }

    /**
     * Visual lightning effect (no damage).
     */
    private static class LightningEffect implements BeatEffect {
        @Override
        public String getId() {
            return "lightning";
        }

        @Override
        public String getName() {
            return "Lightning Strike";
        }

        @Override
        public void trigger(Location location, VisualizationZone zone, double intensity, Collection<Player> viewers) {
            // Spawn lightning effect (visual only, no damage)
            location.getWorld().strikeLightningEffect(location);
        }
    }

    /**
     * Visual explosion effect (particles only, no damage).
     */
    private static class ExplosionVisualEffect implements BeatEffect {
        @Override
        public String getId() {
            return "explosion_visual";
        }

        @Override
        public String getName() {
            return "Explosion Visual";
        }

        @Override
        public void trigger(Location location, VisualizationZone zone, double intensity, Collection<Player> viewers) {
            int count = (int) (30 * intensity);

            for (Player player : viewers) {
                player.spawnParticle(
                    org.bukkit.Particle.EXPLOSION,
                    location,
                    count,
                    2.0, 2.0, 2.0,
                    0.0
                );
                player.spawnParticle(
                    org.bukkit.Particle.FLAME,
                    location,
                    count * 2,
                    3.0, 3.0, 3.0,
                    0.05
                );
            }

            // Play explosion sound
            location.getWorld().playSound(location, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.2f);
        }
    }
}
