package com.audioviz.effects;

import com.audioviz.zones.VisualizationZone;
import com.audioviz.zones.ZoneManager;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages beat-triggered effects for visualization zones.
 * Handles effect registration, zone configurations, and beat event processing.
 *
 * <p>Ported from Paper: AudioVizPlugin → MinecraftServer + ZoneManager,
 * Bukkit particle API → ServerWorld.spawnParticles().
 */
public class BeatEventManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz");

    private final MinecraftServer server;
    private final ZoneManager zoneManager;
    private final Map<String, BeatEffect> registeredEffects;
    private final Map<String, BeatEffectConfig> zoneConfigs;

    public BeatEventManager(MinecraftServer server, ZoneManager zoneManager) {
        this.server = server;
        this.zoneManager = zoneManager;
        this.registeredEffects = new ConcurrentHashMap<>();
        this.zoneConfigs = new ConcurrentHashMap<>();
        registerBuiltInEffects();
    }

    private void registerBuiltInEffects() {
        register(new ParticleBurstEffect());
        register(new ScreenShakeEffect());
        register(new LightningFlashEffect());
        register(new ExplosionVisualEffect());
    }

    public void register(BeatEffect effect) {
        registeredEffects.put(effect.getId(), effect);
        LOGGER.info("Registered beat effect: {}", effect.getId());
    }

    public BeatEffect get(String id) {
        return registeredEffects.get(id);
    }

    public Collection<BeatEffect> getAllEffects() {
        return Collections.unmodifiableCollection(registeredEffects.values());
    }

    public void setZoneConfig(String zoneName, BeatEffectConfig config) {
        zoneConfigs.put(zoneName.toLowerCase(), config);
    }

    public BeatEffectConfig getZoneConfig(String zoneName) {
        return zoneConfigs.get(zoneName.toLowerCase());
    }

    public void removeZoneConfig(String zoneName) {
        zoneConfigs.remove(zoneName.toLowerCase());
    }

    public void clearAllConfigs() {
        zoneConfigs.clear();
    }

    /**
     * Process a beat event for a zone.
     */
    public void processBeat(String zoneName, BeatType beatType, double intensity) {
        BeatEffectConfig config = zoneConfigs.get(zoneName.toLowerCase());
        if (config == null || !config.hasEffects()) return;

        if (intensity < config.getThreshold(beatType)) return;
        if (!config.canTrigger(beatType)) return;

        VisualizationZone zone = zoneManager.getZone(zoneName);
        if (zone == null) return;

        Collection<ServerPlayerEntity> viewers = getViewersNearZone(zone);
        if (viewers.isEmpty()) return;

        Vec3d center = zone.getCenter();
        for (BeatEffect effect : config.getEffects(beatType)) {
            try {
                effect.trigger(center, zone, intensity, viewers);
            } catch (Exception e) {
                LOGGER.warn("Error triggering effect {}: {}", effect.getId(), e.getMessage());
            }
        }

        config.markTriggered(beatType);
    }

    private Collection<ServerPlayerEntity> getViewersNearZone(VisualizationZone zone) {
        Vec3d center = zone.getCenter();
        double maxDistance = Math.max(zone.getSize().length(), 64);
        List<ServerPlayerEntity> viewers = new ArrayList<>();

        ServerWorld world = zone.getWorld();
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.squaredDistanceTo(center) <= maxDistance * maxDistance) {
                viewers.add(player);
            }
        }
        return viewers;
    }

    // ==================== Built-in Effects ====================

    private static class ParticleBurstEffect implements BeatEffect {
        @Override public String getId() { return "particle_burst"; }
        @Override public String getName() { return "Particle Burst"; }

        @Override
        public void trigger(Vec3d location, VisualizationZone zone, double intensity,
                            Collection<ServerPlayerEntity> viewers) {
            int count = (int) (20 * intensity);
            double spread = 2.0 * intensity;
            ServerWorld world = zone.getWorld();
            world.spawnParticles(ParticleTypes.END_ROD,
                location.x, location.y, location.z,
                count, spread, spread, spread, 0.1);
        }
    }

    /**
     * Screen shake using world border warning effect — contracts the border briefly
     * to trigger the red vignette flash, then immediately restores it.
     * Safe with anti-cheat (no player velocity manipulation).
     */
    private static class ScreenShakeEffect implements BeatEffect {
        @Override public String getId() { return "screen_shake"; }
        @Override public String getName() { return "Screen Shake"; }
        @Override public boolean isPerPlayer() { return true; }

        @Override
        public void trigger(Vec3d location, VisualizationZone zone, double intensity,
                            Collection<ServerPlayerEntity> viewers) {
            // Use title packets with brief display to create a visual "flash" effect.
            // A blank title with very short fade-in/stay creates a subtle screen pulse.
            for (ServerPlayerEntity player : viewers) {
                // Send a brief title flash — empty title with quick timings
                player.networkHandler.sendPacket(
                    new net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket(0, 2, 1));
                player.networkHandler.sendPacket(
                    new net.minecraft.network.packet.s2c.play.TitleS2CPacket(
                        net.minecraft.text.Text.literal(" ")));
            }
        }
    }

    /**
     * Lightning flash — particle-based alternative to real lightning entities.
     * Bright flash of END_ROD + FLASH particles centered on the zone.
     */
    private static class LightningFlashEffect implements BeatEffect {
        @Override public String getId() { return "lightning"; }
        @Override public String getName() { return "Lightning Flash"; }

        @Override
        public void trigger(Vec3d location, VisualizationZone zone, double intensity,
                            Collection<ServerPlayerEntity> viewers) {
            ServerWorld world = zone.getWorld();
            // Vertical particle column simulating lightning
            for (int i = 0; i < 5; i++) {
                double y = location.y + i * 2.0;
                world.spawnParticles(ParticleTypes.END_ROD,
                    location.x, y, location.z,
                    (int) (8 * intensity), 0.3, 0.5, 0.3, 0.15);
            }
            // Bright burst at impact point
            world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER,
                location.x, location.y, location.z,
                1, 0.0, 0.0, 0.0, 0.0);
            // Thunder sound
            world.playSound(null, location.x, location.y, location.z,
                SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER,
                0.5f, 1.2f);
        }
    }

    private static class ExplosionVisualEffect implements BeatEffect {
        @Override public String getId() { return "explosion_visual"; }
        @Override public String getName() { return "Explosion Visual"; }

        @Override
        public void trigger(Vec3d location, VisualizationZone zone, double intensity,
                            Collection<ServerPlayerEntity> viewers) {
            ServerWorld world = zone.getWorld();
            int count = (int) (30 * intensity);
            world.spawnParticles(ParticleTypes.EXPLOSION,
                location.x, location.y, location.z,
                count, 2.0, 2.0, 2.0, 0.0);
            world.spawnParticles(ParticleTypes.FLAME,
                location.x, location.y, location.z,
                count * 2, 3.0, 3.0, 3.0, 0.05);
            world.playSound(null, location.x, location.y, location.z,
                SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS,
                0.5f, 1.2f);
        }
    }
}
