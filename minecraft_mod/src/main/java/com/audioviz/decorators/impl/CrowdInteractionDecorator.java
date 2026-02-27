package com.audioviz.decorators.impl;

import com.audioviz.AudioVizMod;
import com.audioviz.decorators.*;
import com.audioviz.patterns.AudioState;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageZoneRole;
import com.audioviz.zones.VisualizationZone;
import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Detects players in the AUDIENCE zone and triggers localized particle effects
 * around them on beats. Particle-only decorator (no display entities).
 *
 * <p>Ported from Paper: Bukkit Particle -> MC ParticleTypes,
 * Color.fromRGB -> packed ARGB int, player.getLocation() -> player.getPos().
 */
public class CrowdInteractionDecorator extends StageDecorator {

    // Packed ARGB colors for frequency bands
    private static final int[] BAND_COLORS = {
        0xFF_E74C3C,  // Bass - Red
        0xFF_E67E22,  // Low-mid - Orange
        0xFF_F1C40F,  // Mid - Yellow
        0xFF_2ECC71,  // High-mid - Green
        0xFF_3498DB   // High - Blue
    };

    private final Map<UUID, Long> playerCooldowns = new HashMap<>();

    public CrowdInteractionDecorator(Stage stage, AudioVizMod mod) {
        super("crowd", "Crowd FX", stage, mod);
    }

    @Override
    public DecoratorConfig getDefaultConfig() {
        DecoratorConfig config = new DecoratorConfig();
        config.set("max_particles_per_player", 15);
        config.set("ambient_enabled", true);
        config.set("cooldown_ticks", 4);
        return config;
    }

    @Override
    public void onActivate() {
        playerCooldowns.clear();
    }

    @Override
    public void onDeactivate() {
        playerCooldowns.clear();
    }

    @Override
    public void tick(AudioState audio, DJInfo djInfo) {
        String audienceZoneName = stage.getZoneName(StageZoneRole.AUDIENCE);
        if (audienceZoneName == null) return;

        VisualizationZone audienceZone = mod.getZoneManager().getZone(audienceZoneName);
        if (audienceZone == null) return;

        ServerWorld world = audienceZone.getWorld();
        if (world == null) return;

        int maxParticles = config.getInt("max_particles_per_player", 15);
        boolean ambientEnabled = config.getBoolean("ambient_enabled", true);
        int cooldownTicks = config.getInt("cooldown_ticks", 4);

        // Find players in the audience zone
        List<ServerPlayerEntity> playersInZone = new ArrayList<>();
        for (ServerPlayerEntity player : world.getPlayers()) {
            Vec3d pos = new Vec3d(player.getX(), player.getY(), player.getZ());
            if (audienceZone.contains(pos)) {
                playersInZone.add(player);
            }
        }

        if (playersInZone.isEmpty()) return;

        int dominant = getDominantBand(audio);
        int bandColor = BAND_COLORS[Math.min(dominant, BAND_COLORS.length - 1)];

        // Beat burst particles
        if (audio.isBeat() && audio.getBeatIntensity() > 0.3) {
            for (ServerPlayerEntity player : playersInZone) {
                Long lastTrigger = playerCooldowns.get(player.getUuid());
                if (lastTrigger != null && (tickCount - lastTrigger) < cooldownTicks) {
                    continue;
                }
                playerCooldowns.put(player.getUuid(), tickCount);

                double px = player.getX();
                double py = player.getY() + 1;
                double pz = player.getZ();
                double intensity = audio.getBeatIntensity();

                if (intensity > 0.7) {
                    world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME,
                        px, py, pz, Math.min(maxParticles, 10), 0.5, 0.5, 0.5, 0.05);
                    world.spawnParticles(ParticleTypes.FIREWORK,
                        px, py, pz, Math.min(maxParticles / 2, 5), 0.3, 0.5, 0.3, 0.08);
                } else if (intensity > 0.5) {
                    world.spawnParticles(ParticleTypes.END_ROD,
                        px, py, pz, Math.min(maxParticles, 8), 0.3, 0.3, 0.3, 0.04);
                } else {
                    DustParticleEffect dust = new DustParticleEffect(bandColor, 1.5f);
                    world.spawnParticles(dust,
                        px, py, pz, Math.min(maxParticles, 6), 0.4, 0.3, 0.4, 0);
                }
            }
        }

        // Ambient particles (every 4th tick if amplitude > 0.3)
        if (ambientEnabled && tickCount % 4 == 0 && audio.getAmplitude() > 0.3) {
            int fromColor = BAND_COLORS[dominant];
            int toColor = darkenColor(fromColor, 0.5f);
            DustColorTransitionParticleEffect transition =
                new DustColorTransitionParticleEffect(fromColor, toColor, 1.0f);

            for (ServerPlayerEntity player : playersInZone) {
                double px = player.getX();
                double py = player.getY();
                double pz = player.getZ();
                world.spawnParticles(transition,
                    px, py, pz, 3, 0.3, 0.05, 0.3, 0);
            }
        }

        // Periodic cleanup of stale cooldown entries
        if (tickCount % 100 == 0) {
            playerCooldowns.entrySet().removeIf(entry -> (tickCount - entry.getValue()) > 200);
        }
    }

    private static int darkenColor(int argb, float factor) {
        int a = (argb >> 24) & 0xFF;
        int r = (int) (((argb >> 16) & 0xFF) * factor);
        int g = (int) (((argb >> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
