package com.audioviz.decorators.impl;

import com.audioviz.AudioVizPlugin;
import com.audioviz.decorators.DJInfo;
import com.audioviz.decorators.DecoratorConfig;
import com.audioviz.decorators.StageDecorator;
import com.audioviz.patterns.AudioState;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageZoneRole;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Detects players in the AUDIENCE zone and triggers localized particle effects
 * around them on beats. Particle-only decorator (no display entities).
 */
public class CrowdInteractionDecorator extends StageDecorator {

    private static final Color[] BAND_COLORS = {
        Color.fromRGB(231, 76, 60),   // Bass - Red
        Color.fromRGB(230, 126, 34),  // Low-mid - Orange
        Color.fromRGB(241, 196, 15),  // Mid - Yellow
        Color.fromRGB(46, 204, 113),  // High-mid - Green
        Color.fromRGB(52, 152, 219)   // High - Blue
    };

    // Per-player cooldown tracking (UUID -> last trigger tick)
    private final Map<UUID, Long> playerCooldowns = new HashMap<>();

    public CrowdInteractionDecorator(Stage stage, AudioVizPlugin plugin) {
        super("crowd", "Crowd FX", stage, plugin);
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
        // No entity pool needed — particle-only decorator
        playerCooldowns.clear();
    }

    @Override
    public void onDeactivate() {
        playerCooldowns.clear();
    }

    @Override
    public void tick(AudioState audio, DJInfo djInfo) {
        // Find the audience zone
        String audienceZoneName = stage.getZoneName(StageZoneRole.AUDIENCE);
        if (audienceZoneName == null) return;

        VisualizationZone audienceZone = plugin.getZoneManager().getZone(audienceZoneName);
        if (audienceZone == null) return;
        if (audienceZone.getOrigin().getWorld() == null) return;

        int maxParticles = config.getInt("max_particles_per_player", 15);
        boolean ambientEnabled = config.getBoolean("ambient_enabled", true);
        int cooldownTicks = config.getInt("cooldown_ticks", 4);

        // Find players in the audience zone
        List<Player> playersInZone = new ArrayList<>();
        for (Player player : audienceZone.getOrigin().getWorld().getPlayers()) {
            if (audienceZone.contains(player.getLocation())) {
                playersInZone.add(player);
            }
        }

        if (playersInZone.isEmpty()) return;

        int dominant = getDominantBand(audio);
        Color bandColor = BAND_COLORS[Math.min(dominant, BAND_COLORS.length - 1)];

        // Beat burst particles
        if (audio.isBeat() && audio.getBeatIntensity() > 0.3) {
            for (Player player : playersInZone) {
                // Check cooldown
                Long lastTrigger = playerCooldowns.get(player.getUniqueId());
                if (lastTrigger != null && (tickCount - lastTrigger) < cooldownTicks) {
                    continue;
                }
                playerCooldowns.put(player.getUniqueId(), tickCount);

                Location loc = player.getLocation().add(0, 1, 0);
                double intensity = audio.getBeatIntensity();

                if (intensity > 0.7) {
                    // High intensity: soul fire + sparks
                    loc.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, loc,
                        Math.min(maxParticles, 10), 0.5, 0.5, 0.5, 0.05);
                    loc.getWorld().spawnParticle(Particle.FIREWORK, loc,
                        Math.min(maxParticles / 2, 5), 0.3, 0.5, 0.3, 0.08);
                } else if (intensity > 0.5) {
                    // Medium: END_ROD upward burst
                    loc.getWorld().spawnParticle(Particle.END_ROD, loc,
                        Math.min(maxParticles, 8), 0.3, 0.3, 0.3, 0.04);
                } else {
                    // Low: colored dust
                    Particle.DustOptions dust = new Particle.DustOptions(bandColor, 1.5f);
                    loc.getWorld().spawnParticle(Particle.DUST, loc,
                        Math.min(maxParticles, 6), 0.4, 0.3, 0.4, 0, dust);
                }
            }
        }

        // Ambient particles (every 4th tick if amplitude > 0.3)
        if (ambientEnabled && tickCount % 4 == 0 && audio.getAmplitude() > 0.3) {
            for (Player player : playersInZone) {
                Location feetLoc = player.getLocation();
                Color ambientColor = BAND_COLORS[dominant];
                Color darker = Color.fromRGB(
                    (int) (ambientColor.getRed() * 0.5),
                    (int) (ambientColor.getGreen() * 0.5),
                    (int) (ambientColor.getBlue() * 0.5)
                );
                Particle.DustTransition transition = new Particle.DustTransition(ambientColor, darker, 1.0f);
                feetLoc.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, feetLoc,
                    3, 0.3, 0.05, 0.3, 0, transition);
            }
        }

        // Periodic cleanup of stale cooldown entries
        if (tickCount % 100 == 0) {
            playerCooldowns.entrySet().removeIf(entry -> (tickCount - entry.getValue()) > 200);
        }
    }
}
