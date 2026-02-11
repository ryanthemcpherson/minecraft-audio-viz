package com.audioviz.bedrock;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Detects Geyser/Floodgate and tracks Bedrock players.
 * All Floodgate API access is reflection-based to avoid compile-time dependencies.
 */
public class BedrockSupport {

    private final Logger logger;
    private final FileConfiguration config;

    // Detection results
    private boolean geyserPresent;
    private boolean floodgatePresent;
    private boolean geyserDisplayEntityPresent;

    // Floodgate API reflection
    private Method floodgateGetInstance;
    private Method floodgateIsFloodgatePlayer;
    private Object floodgateApi;

    // Config
    private boolean autoDetect;
    private boolean forceParticleFallback;
    private int particleQuality;
    private float particleSize;
    private int maxRenderDistance;

    // Tracked Bedrock players
    private final Set<UUID> bedrockPlayers = ConcurrentHashMap.newKeySet();

    public BedrockSupport(Logger logger, FileConfiguration config) {
        this.logger = logger;
        this.config = config;
    }

    /**
     * Detect Geyser, Floodgate, and GeyserDisplayEntity on startup.
     */
    public void detect() {
        // Load config
        autoDetect = config.getBoolean("bedrock.auto_detect", true);
        forceParticleFallback = config.getBoolean("bedrock.force_particle_fallback", false);
        particleQuality = config.getInt("bedrock.particle_quality", 3);
        particleSize = (float) config.getDouble("bedrock.particle_size", 1.8);
        maxRenderDistance = config.getInt("bedrock.max_render_distance", 48);

        if (!autoDetect && !forceParticleFallback) {
            logger.info("[Bedrock] Auto-detect disabled and fallback not forced - Bedrock support inactive");
            return;
        }

        // Check for Geyser-Spigot plugin
        geyserPresent = Bukkit.getPluginManager().getPlugin("Geyser-Spigot") != null;

        // Check for Floodgate plugin
        floodgatePresent = Bukkit.getPluginManager().getPlugin("floodgate") != null;

        // Check for GeyserDisplayEntity extension
        geyserDisplayEntityPresent = false;
        try {
            Class.forName("com.github.camotoy.geyserdisplayentity.GeyserDisplayEntity");
            geyserDisplayEntityPresent = true;
        } catch (ClassNotFoundException ignored) {
            // Not installed
        }

        // Set up Floodgate API via reflection
        if (floodgatePresent) {
            try {
                Class<?> floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                floodgateGetInstance = floodgateApiClass.getMethod("getInstance");
                floodgateIsFloodgatePlayer = floodgateApiClass.getMethod("isFloodgatePlayer", UUID.class);
                floodgateApi = floodgateGetInstance.invoke(null);
                logger.info("[Bedrock] Floodgate API loaded via reflection");
            } catch (Exception e) {
                logger.warning("[Bedrock] Failed to load Floodgate API: " + e.getMessage());
                floodgateApi = null;
            }
        }

        // Log detection results
        if (!geyserPresent) {
            logger.info("[Bedrock] Geyser not detected - Bedrock support inactive");
            return;
        }

        logger.info("[Bedrock] Geyser detected!");
        logger.info("[Bedrock] Floodgate: " + (floodgatePresent ? "present" : "not found (using UUID prefix fallback)"));
        logger.info("[Bedrock] GeyserDisplayEntity: " + (geyserDisplayEntityPresent ? "present" : "not installed"));

        if (needsParticleFallback()) {
            logger.info("[Bedrock] Particle fallback ENABLED - Bedrock players will see per-player particles");
        } else if (geyserDisplayEntityPresent) {
            logger.info("[Bedrock] GeyserDisplayEntity detected - particle fallback not needed");
        }
    }

    /**
     * Check if a player is a Bedrock player.
     * Uses Floodgate API if available, falls back to UUID prefix check.
     */
    public boolean isBedrockPlayer(UUID uuid) {
        // Try Floodgate API first
        if (floodgateApi != null) {
            try {
                return (boolean) floodgateIsFloodgatePlayer.invoke(floodgateApi, uuid);
            } catch (Exception e) {
                // Fall through to UUID check
            }
        }

        // Fallback: Geyser/Floodgate Bedrock UUIDs start with 00000000-0000-0000-0009-
        return uuid.toString().startsWith("00000000-0000-0000-0009-");
    }

    /**
     * Track a player on join. If they're a Bedrock player, add them to the set.
     */
    public void onPlayerJoin(Player player) {
        if (!isActive()) return;

        if (isBedrockPlayer(player.getUniqueId())) {
            bedrockPlayers.add(player.getUniqueId());
            logger.info("[Bedrock] Bedrock player joined: " + player.getName() +
                (needsParticleFallback() ? " (particle fallback active)" : ""));
        }
    }

    /**
     * Remove a player on quit.
     */
    public void onPlayerQuit(UUID uuid) {
        bedrockPlayers.remove(uuid);
    }

    /**
     * Returns true when Geyser is present but GeyserDisplayEntity is not,
     * OR when force_particle_fallback is enabled.
     */
    public boolean needsParticleFallback() {
        if (forceParticleFallback) return true;
        return geyserPresent && !geyserDisplayEntityPresent;
    }

    /**
     * Fast check: are there any Bedrock players currently online?
     */
    public boolean hasBedrockPlayersOnline() {
        return !bedrockPlayers.isEmpty();
    }

    /**
     * Get the set of tracked Bedrock player UUIDs.
     */
    public Set<UUID> getBedrockPlayers() {
        return Collections.unmodifiableSet(bedrockPlayers);
    }

    /**
     * Is Bedrock support active (Geyser detected or force-enabled)?
     */
    public boolean isActive() {
        return geyserPresent || forceParticleFallback;
    }

    // Getters for detection status
    public boolean isGeyserPresent() { return geyserPresent; }
    public boolean isFloodgatePresent() { return floodgatePresent; }
    public boolean isGeyserDisplayEntityPresent() { return geyserDisplayEntityPresent; }

    // Config getters
    public int getParticleQuality() { return particleQuality; }
    public float getParticleSize() { return particleSize; }
    public int getMaxRenderDistance() { return maxRenderDistance; }
}
