package com.audioviz.connection;

import com.audioviz.AudioVizPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Logger;

/**
 * Monitors DJ connection state and audio frame freshness.
 * Provides automatic visual feedback in-game when connection changes.
 */
public class ConnectionStateListener {

    private final AudioVizPlugin plugin;
    private final Logger logger;

    private volatile boolean djConnected = false;
    private volatile long lastFrameMs = 0;
    private volatile boolean stale = false;

    // Brightness ramp state
    private double currentBrightness = 1.0;
    private double targetBrightness = 1.0;
    private int rampTicksTotal = 0;
    private int rampTicksElapsed = 0;

    private BukkitTask tickTask;

    private static final long STALE_THRESHOLD_MS = 3000;
    private static final int RAMP_DURATION_TICKS = 20;
    private static final double DIM_BRIGHTNESS = 0.3;

    public ConnectionStateListener(AudioVizPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /** Start the staleness check tick loop (call from onEnable). */
    public void start() {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    /** Stop the tick loop (call from onDisable). */
    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    /** Called by VizWebSocketServer when a DJ client connects. */
    public void onDjConnect(String info) {
        djConnected = true;
        stale = false;
        logger.info("DJ connected: " + info);
        Bukkit.getScheduler().runTask(plugin, () -> {
            startBrightnessRamp(1.0);
            broadcastActionBar(Component.text("DJ connected", NamedTextColor.GREEN));
            spawnConnectionParticles(true);
        });
    }

    /** Called by VizWebSocketServer when a DJ client disconnects. */
    public void onDjDisconnect(String reason) {
        djConnected = false;
        logger.info("DJ disconnected: " + reason);
        Bukkit.getScheduler().runTask(plugin, () -> {
            broadcastActionBar(Component.text("DJ disconnected", NamedTextColor.RED));
            spawnConnectionParticles(false);
        });
    }

    /** Called by BitmapPatternManager when an audio frame is received. */
    public void onAudioFrame() {
        lastFrameMs = System.currentTimeMillis();
        if (stale) {
            stale = false;
            Bukkit.getScheduler().runTask(plugin, () -> {
                startBrightnessRamp(1.0);
                broadcastActionBar(Component.text("Audio signal restored", NamedTextColor.GREEN));
            });
        }
    }

    // ========== Tick Loop ==========

    private void tick() {
        long now = System.currentTimeMillis();

        // Check staleness
        if (djConnected && !stale && lastFrameMs > 0 && isStale(lastFrameMs, now, STALE_THRESHOLD_MS)) {
            stale = true;
            startBrightnessRamp(DIM_BRIGHTNESS);
            broadcastActionBar(Component.text("Audio signal lost", NamedTextColor.YELLOW));
        }

        // Apply brightness ramp
        if (rampTicksTotal > 0 && rampTicksElapsed < rampTicksTotal) {
            rampTicksElapsed++;
            double brightness = computeRampedBrightness(
                currentBrightness, targetBrightness, rampTicksTotal, rampTicksElapsed);
            var effects = plugin.getGlobalBitmapEffects();
            if (effects != null) {
                effects.setBrightness(brightness);
            }
            if (rampTicksElapsed >= rampTicksTotal) {
                currentBrightness = targetBrightness;
                rampTicksTotal = 0;
            }
        }
    }

    // ========== Pure Logic (testable) ==========

    /** Check if audio is stale (no frames for longer than threshold). */
    public static boolean isStale(long lastFrameMs, long nowMs, long thresholdMs) {
        if (lastFrameMs == 0) return true;
        return (nowMs - lastFrameMs) > thresholdMs;
    }

    /** Compute brightness during a linear ramp. */
    public static double computeRampedBrightness(
            double fromBrightness, double toBrightness, int totalTicks, int elapsedTicks) {
        if (totalTicks <= 0) return toBrightness;
        double t = Math.min(1.0, (double) elapsedTicks / totalTicks);
        return fromBrightness + (toBrightness - fromBrightness) * t;
    }

    // ========== Helpers ==========

    private void startBrightnessRamp(double target) {
        this.targetBrightness = target;
        this.rampTicksTotal = RAMP_DURATION_TICKS;
        this.rampTicksElapsed = 0;
        var effects = plugin.getGlobalBitmapEffects();
        if (effects != null) {
            this.currentBrightness = effects.getBrightness();
        }
    }

    private void broadcastActionBar(Component message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendActionBar(message);
            }
        });
    }

    private void spawnConnectionParticles(boolean connected) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            var zones = plugin.getZoneManager().getAllZones();
            Particle particle = connected ? Particle.HAPPY_VILLAGER : Particle.SMALL_FLAME;
            for (var zone : zones) {
                var center = zone.getCenter();
                if (center != null && center.getWorld() != null) {
                    center.getWorld().spawnParticle(particle, center, 20, 2, 2, 2, 0);
                }
            }
        });
    }

    // ========== Accessors ==========

    public boolean isDjConnected() { return djConnected; }
    public boolean isAudioStale() { return stale; }
    public long getLastFrameMs() { return lastFrameMs; }
}
