package com.audioviz.particles;

import com.audioviz.AudioVizPlugin;
import com.audioviz.bedrock.BedrockSupport;
import com.audioviz.patterns.AudioState;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Particle-based visualization for Bedrock compatibility.
 * Uses particles instead of Display Entities so Bedrock players via Geyser can see the visualization.
 *
 * Render Modes:
 * - "entities" (default): Display Entities only (Java Edition)
 * - "particles": Particles only (Bedrock Compatible)
 * - "hybrid": Both particles and entities (Mixed server)
 */
public class ParticleVisualizationManager {

    private final AudioVizPlugin plugin;
    private final Logger logger;
    private final BedrockSupport bedrockSupport;

    // Per-zone configuration
    private final Map<String, ParticleVizConfig> zoneConfigs = new ConcurrentHashMap<>();

    // Current audio state for rendering
    private volatile AudioState currentAudioState;

    // Render task
    private BukkitTask renderTask;

    // Particle spawn throttling (per-zone to prevent starvation in multi-zone setups)
    private final Map<String, Integer> particlesThisTickByZone = new ConcurrentHashMap<>();
    private static final int MAX_PARTICLES_PER_TICK_PER_ZONE = 200;
    private static final int MAX_PARTICLES_PER_TICK_GLOBAL = 500;

    public ParticleVisualizationManager(AudioVizPlugin plugin, BedrockSupport bedrockSupport) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.bedrockSupport = bedrockSupport;
    }

    /**
     * Start the particle render loop.
     */
    public void start() {
        // Render particles every 2 ticks (10 FPS) to reduce load
        renderTask = plugin.getServer().getScheduler().runTaskTimer(
            plugin, this::renderTick, 2L, 2L
        );
        logger.info("ParticleVisualizationManager started");
    }

    /**
     * Stop the particle render loop.
     */
    public void stop() {
        if (renderTask != null) {
            renderTask.cancel();
            renderTask = null;
        }
        logger.info("ParticleVisualizationManager stopped");
    }

    /**
     * Set the render mode for a zone.
     * @param zoneName Zone name
     * @param mode "entities", "particles", or "hybrid"
     */
    public void setRenderMode(String zoneName, String mode) {
        getOrCreateConfig(zoneName).setRenderMode(mode);
        logger.info("Zone '" + zoneName + "' render mode set to: " + mode);
    }

    /**
     * Get the render mode for a zone.
     */
    public String getRenderMode(String zoneName) {
        return getOrCreateConfig(zoneName).getRenderMode();
    }

    /**
     * Check if particles should be rendered for a zone.
     */
    public boolean shouldRenderParticles(String zoneName) {
        String mode = getRenderMode(zoneName);
        return "particles".equals(mode) || "hybrid".equals(mode);
    }

    /**
     * Check if entities should be rendered for a zone.
     */
    public boolean shouldRenderEntities(String zoneName) {
        String mode = getRenderMode(zoneName);
        return "entities".equals(mode) || "hybrid".equals(mode);
    }

    /**
     * Update audio state for rendering.
     */
    public void updateAudioState(AudioState state) {
        this.currentAudioState = state;
    }

    /**
     * Configure particle visualization for a zone.
     */
    public void configureZone(String zoneName, ParticleVizConfig config) {
        zoneConfigs.put(zoneName.toLowerCase(), config);
    }

    /**
     * Get or create configuration for a zone.
     */
    public ParticleVizConfig getOrCreateConfig(String zoneName) {
        return zoneConfigs.computeIfAbsent(zoneName.toLowerCase(), k -> new ParticleVizConfig());
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
     * Main render tick - spawns particles based on audio state.
     */
    private void renderTick() {
        if (currentAudioState == null) return;

        // Reset per-zone counters
        particlesThisTickByZone.clear();

        for (Map.Entry<String, ParticleVizConfig> entry : zoneConfigs.entrySet()) {
            String zoneName = entry.getKey();
            ParticleVizConfig config = entry.getValue();

            if (!shouldRenderParticles(zoneName)) continue;

            VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
            if (zone == null) continue;

            renderZone(zone, zoneName, config, currentAudioState);
        }
    }

    /**
     * Render particles for a single zone.
     */
    private void renderZone(VisualizationZone zone, String zoneName, ParticleVizConfig config, AudioState audio) {
        double[] bands = audio.getBands();
        if (bands == null || bands.length < 5) return;

        int density = config.getDensity();
        Particle particleType = config.getParticleType();
        String colorMode = config.getColorMode();
        float particleSize = config.getParticleSize();
        boolean trail = config.hasTrail();

        // Calculate particle positions based on pattern
        // Using EQ Bars style - columns for each frequency band (5 bands)
        int columnsPerBand = Math.max(1, density);
        double spacing = 1.0 / (5 * columnsPerBand);

        for (int band = 0; band < 5; band++) {
            double intensity = bands[band];
            if (intensity < 0.05) continue; // Skip very quiet bands

            // Get color for this band
            Color color = getColorForBand(band, intensity, colorMode, config, audio);

            for (int col = 0; col < columnsPerBand; col++) {
                if (getZoneParticleCount(zoneName) >= MAX_PARTICLES_PER_TICK_PER_ZONE) return;
                if (getTotalParticleCount() >= MAX_PARTICLES_PER_TICK_GLOBAL) return;

                // X position based on band and column
                double nx = (band * columnsPerBand + col + 0.5) * spacing;

                // Y position based on intensity (height of the bar)
                int particleCount = (int) Math.ceil(intensity * 5 * density);

                for (int y = 0; y < particleCount; y++) {
                    if (getZoneParticleCount(zoneName) >= MAX_PARTICLES_PER_TICK_PER_ZONE) return;
                    if (getTotalParticleCount() >= MAX_PARTICLES_PER_TICK_GLOBAL) return;

                    double ny = (y + 0.5) / (5.0 * density);
                    double nz = 0.5; // Center depth

                    // Add slight variation
                    nx += (Math.random() - 0.5) * 0.02;
                    nz += (Math.random() - 0.5) * 0.1;

                    Location loc = zone.localToWorld(nx, ny, nz);
                    spawnParticle(loc, particleType, color, particleSize, trail, intensity);
                    incrementZoneParticleCount(zoneName);
                }
            }
        }

        // Beat burst effect
        if (audio.isBeat() && audio.getBeatIntensity() > 0.5) {
            spawnBeatBurst(zone, zoneName, config, audio);
        }
    }

    private int getZoneParticleCount(String zoneName) {
        return particlesThisTickByZone.getOrDefault(zoneName, 0);
    }

    private int getTotalParticleCount() {
        return particlesThisTickByZone.values().stream().mapToInt(Integer::intValue).sum();
    }

    private void incrementZoneParticleCount(String zoneName) {
        particlesThisTickByZone.merge(zoneName, 1, Integer::sum);
    }

    /**
     * Spawn a single particle. When Bedrock fallback is active and Bedrock players are online,
     * particles are sent per-player so only Bedrock players see them.
     */
    private void spawnParticle(Location loc, Particle type, Color color, float size, boolean trail, double intensity) {
        boolean bedrockTargeted = bedrockSupport != null
            && bedrockSupport.needsParticleFallback()
            && bedrockSupport.hasBedrockPlayersOnline();

        try {
            if (type == Particle.DUST) {
                float effectiveSize = bedrockTargeted ? size * bedrockSupport.getParticleSize() / 1.5f : size;
                Particle.DustOptions dust = new Particle.DustOptions(color, effectiveSize);
                if (bedrockTargeted) {
                    spawnForBedrockPlayers(loc, Particle.DUST, 1, 0, 0, 0, 0, dust);
                } else {
                    loc.getWorld().spawnParticle(Particle.DUST, loc, 1, 0, 0, 0, 0, dust);
                }
            } else if (type == Particle.DUST_COLOR_TRANSITION) {
                Color darker = Color.fromRGB(
                    (int)(color.getRed() * 0.5),
                    (int)(color.getGreen() * 0.5),
                    (int)(color.getBlue() * 0.5)
                );
                float effectiveSize = bedrockTargeted ? size * bedrockSupport.getParticleSize() / 1.5f : size;
                Particle.DustTransition transition = new Particle.DustTransition(color, darker, effectiveSize);
                if (bedrockTargeted) {
                    spawnForBedrockPlayers(loc, Particle.DUST_COLOR_TRANSITION, 1, 0, 0, 0, 0, transition);
                } else {
                    loc.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, loc, 1, 0, 0, 0, 0, transition);
                }
            } else {
                double speed = trail ? 0.05 * intensity : 0.01;
                if (bedrockTargeted) {
                    spawnForBedrockPlayers(loc, type, 1, 0.02, 0.02, 0.02, speed, null);
                } else {
                    loc.getWorld().spawnParticle(type, loc, 1, 0.02, 0.02, 0.02, speed);
                }
            }
        } catch (Exception e) {
            logger.fine("Particle spawn error: " + e.getMessage());
        }
    }

    /**
     * Send particles only to Bedrock players (per-player targeting).
     * Java players never see these particles.
     */
    private <T> void spawnForBedrockPlayers(Location loc, Particle type, int count,
                                             double offX, double offY, double offZ,
                                             double speed, T data) {
        int maxDist = bedrockSupport.getMaxRenderDistance();
        double maxDistSq = (double) maxDist * maxDist;

        for (UUID uuid : bedrockSupport.getBedrockPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;
            if (!player.getWorld().equals(loc.getWorld())) continue;
            if (player.getLocation().distanceSquared(loc) > maxDistSq) continue;

            if (data != null) {
                player.spawnParticle(type, loc, count, offX, offY, offZ, speed, data);
            } else {
                player.spawnParticle(type, loc, count, offX, offY, offZ, speed);
            }
        }
    }

    /**
     * Spawn a beat burst effect.
     */
    private void spawnBeatBurst(VisualizationZone zone, String zoneName, ParticleVizConfig config, AudioState audio) {
        Location center = zone.localToWorld(0.5, 0.3, 0.5);
        double intensity = audio.getBeatIntensity();
        int count = (int)(20 * intensity * config.getDensity());

        // Limit based on per-zone and global limits
        int zoneRemaining = MAX_PARTICLES_PER_TICK_PER_ZONE - getZoneParticleCount(zoneName);
        int globalRemaining = MAX_PARTICLES_PER_TICK_GLOBAL - getTotalParticleCount();
        count = Math.min(count, Math.min(zoneRemaining, globalRemaining));

        if (count <= 0) return;

        Color color = getColorForBand(1, intensity, config.getColorMode(), config, audio); // Bass color

        boolean bedrockTargeted = bedrockSupport != null
            && bedrockSupport.needsParticleFallback()
            && bedrockSupport.hasBedrockPlayersOnline();

        try {
            // Ring burst
            for (int i = 0; i < count; i++) {
                double angle = (2 * Math.PI * i) / count;
                double radius = 0.5 + intensity * 0.3;
                double x = center.getX() + radius * Math.cos(angle);
                double z = center.getZ() + radius * Math.sin(angle);
                Location loc = new Location(center.getWorld(), x, center.getY(), z);

                if (config.getParticleType() == Particle.DUST) {
                    float burstSize = config.getParticleSize() * 1.5f;
                    if (bedrockTargeted) {
                        burstSize = burstSize * bedrockSupport.getParticleSize() / 1.5f;
                    }
                    Particle.DustOptions dust = new Particle.DustOptions(color, burstSize);
                    if (bedrockTargeted) {
                        spawnForBedrockPlayers(loc, Particle.DUST, 1, 0, 0.1, 0, 0.02, dust);
                    } else {
                        center.getWorld().spawnParticle(Particle.DUST, loc, 1, 0, 0.1, 0, 0.02, dust);
                    }
                } else {
                    if (bedrockTargeted) {
                        spawnForBedrockPlayers(loc, config.getParticleType(), 1, 0, 0.1, 0, 0.05, null);
                    } else {
                        center.getWorld().spawnParticle(config.getParticleType(), loc, 1, 0, 0.1, 0, 0.05);
                    }
                }
                incrementZoneParticleCount(zoneName);
            }
        } catch (Exception e) {
            logger.warning("Error spawning beat burst particles: " + e.getMessage());
        }
    }

    /**
     * Get color based on frequency band and color mode.
     */
    private Color getColorForBand(int band, double intensity, String colorMode, ParticleVizConfig config, AudioState audio) {
        switch (colorMode) {
            case "frequency":
                return getBandColor(band);
            case "rainbow":
                // Cycle through hue based on time
                float hue = (System.currentTimeMillis() % 5000) / 5000f;
                return hsbToColor(hue, 1.0f, (float)Math.max(0.5, intensity));
            case "intensity":
                // Red to green based on intensity
                return Color.fromRGB(
                    (int)(255 * (1 - intensity)),
                    (int)(255 * intensity),
                    50
                );
            case "fixed":
                return config.getFixedColor();
            default:
                return getBandColor(band);
        }
    }

    /**
     * Get the standard color for each frequency band (5-band system).
     */
    private Color getBandColor(int band) {
        switch (band) {
            case 0: return Color.fromRGB(231, 76, 60);   // Bass (40-250Hz) - Red
            case 1: return Color.fromRGB(230, 126, 34);  // Low-mid (250-500Hz) - Orange
            case 2: return Color.fromRGB(241, 196, 15);  // Mid (500-2000Hz) - Yellow
            case 3: return Color.fromRGB(46, 204, 113);  // High-mid (2-6kHz) - Green
            case 4: return Color.fromRGB(52, 152, 219);  // High/Air (6-20kHz) - Blue
            default: return Color.WHITE;
        }
    }

    /**
     * Convert HSB to Bukkit Color.
     */
    private Color hsbToColor(float hue, float saturation, float brightness) {
        int rgb = java.awt.Color.HSBtoRGB(hue, saturation, brightness);
        return Color.fromRGB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    /**
     * Configuration class for particle visualization.
     */
    public static class ParticleVizConfig {
        private String renderMode = "entities";
        private Particle particleType = Particle.DUST;
        private int density = 3;
        private String colorMode = "frequency";
        private Color fixedColor = Color.AQUA;
        private float particleSize = 1.5f;
        private boolean trail = false;

        public String getRenderMode() { return renderMode; }
        public void setRenderMode(String mode) { this.renderMode = mode; }

        public Particle getParticleType() { return particleType; }
        public void setParticleType(Particle type) { this.particleType = type; }
        public void setParticleType(String typeName) {
            try {
                this.particleType = Particle.valueOf(typeName.toUpperCase());
            } catch (IllegalArgumentException e) {
                this.particleType = Particle.DUST;
            }
        }

        public int getDensity() { return density; }
        public void setDensity(int density) { this.density = Math.max(1, Math.min(10, density)); }

        public String getColorMode() { return colorMode; }
        public void setColorMode(String mode) { this.colorMode = mode; }

        public Color getFixedColor() { return fixedColor; }
        public void setFixedColor(Color color) { this.fixedColor = color; }
        public void setFixedColor(String hex) {
            try {
                int rgb = Integer.parseInt(hex.replace("#", ""), 16);
                this.fixedColor = Color.fromRGB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
            } catch (NumberFormatException e) {
                this.fixedColor = Color.AQUA;
            }
        }

        public float getParticleSize() { return particleSize; }
        public void setParticleSize(float size) { this.particleSize = Math.max(0.5f, Math.min(3.0f, size)); }

        public boolean hasTrail() { return trail; }
        public void setTrail(boolean trail) { this.trail = trail; }
    }
}
