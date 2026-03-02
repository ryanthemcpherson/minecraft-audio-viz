package com.audioviz.metrics;

import com.audioviz.AudioVizMod;
import com.audioviz.connection.ConnectionStateListener;
import com.audioviz.decorators.DJInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Toggleable per-player action bar metrics display.
 * Fabric port: uses action bar messages instead of Bukkit per-player scoreboards.
 * Updates every second (20 ticks) for active viewers.
 */
public class MetricsDisplay {

    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz");
    private final Set<UUID> activeViewers = new HashSet<>();

    private int tickCounter = 0;
    private static final int UPDATE_INTERVAL_TICKS = 20;

    /** Called from AudioVizMod.tick() every server tick. */
    public void tick() {
        tickCounter++;
        if (tickCounter % UPDATE_INTERVAL_TICKS != 0) return;
        if (activeViewers.isEmpty()) return;

        AudioVizMod mod = AudioVizMod.getInstance();
        if (mod == null) return;
        MinecraftServer server = mod.getServer();
        if (server == null) return;

        String line = buildMetricsLine(mod);

        for (UUID uuid : new ArrayList<>(activeViewers)) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null || player.isDisconnected()) {
                activeViewers.remove(uuid);
                continue;
            }
            player.sendMessage(Text.literal(line).formatted(Formatting.AQUA), true);
        }
    }

    public boolean toggle(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        if (activeViewers.contains(uuid)) {
            activeViewers.remove(uuid);
            return false;
        } else {
            activeViewers.add(uuid);
            return true;
        }
    }

    public boolean isActive(UUID uuid) {
        return activeViewers.contains(uuid);
    }

    public void stop() {
        activeViewers.clear();
    }

    public void handleDisconnect(UUID uuid) {
        activeViewers.remove(uuid);
    }

    // ========== Metrics Collection ==========

    private String buildMetricsLine(AudioVizMod mod) {
        StringBuilder sb = new StringBuilder();

        // DJ status
        sb.append("DJ: ").append(collectDjStatus(mod));

        // Zones
        var zm = mod.getZoneManager();
        if (zm != null) {
            sb.append(" | Zones: ").append(zm.getAllZones().size());
        }

        // Latency
        var lt = mod.getLatencyTracker();
        if (lt != null && lt.getNetworkStats().getCount() > 0) {
            sb.append(" | Lat: ").append(String.format("%.0fms", lt.getTotalAvgMs()));
        }

        // Sequences
        var sm = mod.getSequenceManager();
        if (sm != null && sm.getActiveCount() > 0) {
            sb.append(" | Seq: ").append(sm.getActiveCount());
        }

        // Recording status
        var rm = mod.getRecordingManager();
        if (rm != null) {
            if (rm.isRecording()) sb.append(" | REC");
            else if (rm.isReplaying()) sb.append(" | PLAY");
        }

        return sb.toString();
    }

    private String collectDjStatus(AudioVizMod mod) {
        ConnectionStateListener conn = mod.getConnectionStateListener();
        boolean connected = conn != null && conn.isDjConnected();
        boolean stale = conn != null && conn.isAudioStale();

        double bpm = 0;
        double confidence = 0;

        var decoratorManager = mod.getStageDecoratorManager();
        if (decoratorManager != null) {
            DJInfo djInfo = decoratorManager.getCurrentDJInfo();
            if (djInfo != null && djInfo.isPresent()) {
                bpm = djInfo.bpm();
                confidence = bpm > 0 ? 0.9 : 0;
            }
        }

        return formatDjStatus(connected, stale, bpm, confidence);
    }

    // ========== Pure Formatting (testable) ==========

    public static String formatRenderTime(double ms) {
        return String.format("%.1fms", ms);
    }

    public static String formatEntityCount(int active, int max) {
        return active + "/" + max;
    }

    public static String formatBpm(double bpm, double confidence) {
        if (confidence < 0.5) return "-- BPM";
        return Math.round(bpm) + " BPM";
    }

    public static String formatDjStatus(boolean connected, boolean stale, double bpm, double confidence) {
        if (!connected) return "Disconnected";
        if (stale) return "Signal Lost";
        return "Connected (" + formatBpm(bpm, confidence) + ")";
    }
}
