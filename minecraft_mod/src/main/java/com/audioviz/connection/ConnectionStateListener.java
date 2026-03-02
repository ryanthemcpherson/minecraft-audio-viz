package com.audioviz.connection;

import com.audioviz.AudioVizMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Monitors DJ connection state and audio frame freshness.
 * Provides automatic visual feedback in-game when connection changes.
 * Fabric port: tick-based instead of BukkitTask, Text.literal instead of ChatColor.
 */
public class ConnectionStateListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz");

    private volatile boolean djConnected = false;
    private volatile long lastFrameMs = 0;
    private volatile boolean stale = false;

    // Brightness ramp state
    private double currentBrightness = 1.0;
    private double targetBrightness = 1.0;
    private int rampTicksTotal = 0;
    private int rampTicksElapsed = 0;

    private int tickCounter = 0;

    private static final long STALE_THRESHOLD_MS = 3000;
    private static final int RAMP_DURATION_TICKS = 20;
    private static final double DIM_BRIGHTNESS = 0.3;
    private static final int CHECK_INTERVAL_TICKS = 10;

    /** Called from AudioVizMod.tick() every server tick. */
    public void tick() {
        tickCounter++;
        if (tickCounter % CHECK_INTERVAL_TICKS != 0) {
            // Only apply brightness ramp on non-check ticks
            applyBrightnessRamp();
            return;
        }

        long now = System.currentTimeMillis();

        // Skip staleness detection during recording playback
        var mod = AudioVizMod.getInstance();
        var recorder = mod != null ? mod.getRecordingManager() : null;
        boolean playingBack = recorder != null && recorder.isReplaying();

        // Check staleness
        if (djConnected && !stale && !playingBack && lastFrameMs > 0 && isStale(lastFrameMs, now, STALE_THRESHOLD_MS)) {
            stale = true;
            startBrightnessRamp(DIM_BRIGHTNESS);
            broadcastActionBar(Text.literal("Audio signal lost").formatted(Formatting.YELLOW));
        }

        applyBrightnessRamp();
    }

    /** Called by VizWebSocketServer when a DJ client connects. */
    public void onDjConnect(String info) {
        djConnected = true;
        stale = false;
        LOGGER.info("DJ connected: {}", info);
        var server = getServer();
        if (server != null) {
            server.execute(() -> {
                startBrightnessRamp(1.0);
                broadcastActionBar(Text.literal("DJ connected").formatted(Formatting.GREEN));
            });
        }
    }

    /** Called by VizWebSocketServer when a DJ client disconnects. */
    public void onDjDisconnect(String reason) {
        djConnected = false;
        LOGGER.info("DJ disconnected: {}", reason);
        var server = getServer();
        if (server != null) {
            server.execute(() -> {
                broadcastActionBar(Text.literal("DJ disconnected").formatted(Formatting.RED));
            });
        }
    }

    /** Called by BitmapPatternManager when an audio frame is received. */
    public void onAudioFrame() {
        lastFrameMs = System.currentTimeMillis();
        if (stale) {
            stale = false;
            var server = getServer();
            if (server != null) {
                server.execute(() -> {
                    startBrightnessRamp(1.0);
                    broadcastActionBar(Text.literal("Audio signal restored").formatted(Formatting.GREEN));
                });
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

    private void applyBrightnessRamp() {
        if (rampTicksTotal > 0 && rampTicksElapsed < rampTicksTotal) {
            rampTicksElapsed++;
            double brightness = computeRampedBrightness(
                currentBrightness, targetBrightness, rampTicksTotal, rampTicksElapsed);
            var mod = AudioVizMod.getInstance();
            if (mod != null) {
                var bpm = mod.getBitmapPatternManager();
                if (bpm != null) {
                    var effects = bpm.getEffectsProcessor();
                    if (effects != null) {
                        effects.setBrightness(brightness);
                    }
                }
            }
            if (rampTicksElapsed >= rampTicksTotal) {
                currentBrightness = targetBrightness;
                rampTicksTotal = 0;
            }
        }
    }

    private void startBrightnessRamp(double target) {
        this.targetBrightness = target;
        this.rampTicksTotal = RAMP_DURATION_TICKS;
        this.rampTicksElapsed = 0;
        var mod = AudioVizMod.getInstance();
        if (mod != null) {
            var bpm = mod.getBitmapPatternManager();
            if (bpm != null) {
                var effects = bpm.getEffectsProcessor();
                if (effects != null) {
                    this.currentBrightness = effects.getBrightness();
                }
            }
        }
    }

    private void broadcastActionBar(Text message) {
        var server = getServer();
        if (server == null) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(message, true);
        }
    }

    private MinecraftServer getServer() {
        var mod = AudioVizMod.getInstance();
        return mod != null ? mod.getServer() : null;
    }

    // ========== Accessors ==========

    public boolean isDjConnected() { return djConnected; }
    public boolean isAudioStale() { return stale; }
    public long getLastFrameMs() { return lastFrameMs; }
}
