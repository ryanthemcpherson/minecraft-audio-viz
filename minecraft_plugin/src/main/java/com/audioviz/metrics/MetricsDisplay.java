package com.audioviz.metrics;

import com.audioviz.AudioVizPlugin;
import com.audioviz.connection.ConnectionStateListener;
import com.audioviz.decorators.DJInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import java.util.*;

/**
 * Toggleable per-player scoreboard sidebar displaying real-time MCAV metrics.
 */
public class MetricsDisplay {

    private final AudioVizPlugin plugin;
    private final Set<UUID> activeViewers = new HashSet<>();
    private final Map<UUID, Scoreboard> playerScoreboards = new HashMap<>();
    private BukkitTask updateTask;

    private static final String OBJECTIVE_NAME = "mcav_metrics";
    private static final long UPDATE_INTERVAL_TICKS = 20L;

    public MetricsDisplay(AudioVizPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::update, 20L, UPDATE_INTERVAL_TICKS);
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        for (UUID uuid : new ArrayList<>(activeViewers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                removeScoreboard(player);
            }
        }
        activeViewers.clear();
        playerScoreboards.clear();
    }

    public boolean toggle(Player player) {
        if (activeViewers.contains(player.getUniqueId())) {
            removeScoreboard(player);
            activeViewers.remove(player.getUniqueId());
            playerScoreboards.remove(player.getUniqueId());
            return false;
        } else {
            activeViewers.add(player.getUniqueId());
            return true;
        }
    }

    public boolean isActive(UUID uuid) {
        return activeViewers.contains(uuid);
    }

    private void update() {
        if (activeViewers.isEmpty()) return;

        String djStatus = collectDjStatus();
        String entities = collectEntityCount();
        String activeZones = collectActiveZones();
        String renderTime = collectRenderTime();
        String sequences = collectSequences();
        String latency = collectLatency();
        String recordingStatus = collectRecordingStatus();

        for (UUID uuid : new ArrayList<>(activeViewers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                activeViewers.remove(uuid);
                playerScoreboards.remove(uuid);
                continue;
            }
            updateScoreboard(player, djStatus, entities, activeZones, renderTime, sequences, latency, recordingStatus);
        }
    }

    private void updateScoreboard(Player player, String djStatus, String entities,
                                   String activeZones, String renderTime, String sequences,
                                   String latency, String recordingStatus) {
        Scoreboard board = playerScoreboards.computeIfAbsent(player.getUniqueId(), k -> {
            Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
            player.setScoreboard(sb);
            return sb;
        });

        Objective existing = board.getObjective(OBJECTIVE_NAME);
        if (existing != null) existing.unregister();

        Objective obj = board.registerNewObjective(OBJECTIVE_NAME,
            Criteria.DUMMY, Component.text("MCAV Metrics", NamedTextColor.AQUA));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        obj.getScore("DJ: " + djStatus).setScore(8);
        obj.getScore("Entities: " + entities).setScore(7);
        obj.getScore("Zones: " + activeZones).setScore(6);
        obj.getScore("Render: " + renderTime).setScore(5);
        obj.getScore("Latency: " + latency).setScore(4);
        obj.getScore("Sequences: " + sequences).setScore(3);
        if (recordingStatus != null) {
            obj.getScore("Recording: " + recordingStatus).setScore(2);
        }
    }

    private void removeScoreboard(Player player) {
        Scoreboard board = playerScoreboards.get(player.getUniqueId());
        if (board != null) {
            Objective obj = board.getObjective(OBJECTIVE_NAME);
            if (obj != null) obj.unregister();
        }
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    private String collectDjStatus() {
        ConnectionStateListener conn = plugin.getConnectionStateListener();
        boolean connected = conn != null && conn.isDjConnected();
        boolean stale = conn != null && conn.isAudioStale();

        double bpm = 0;
        double confidence = 0;

        // Get BPM from DJ info if available
        var decoratorManager = plugin.getDecoratorManager();
        if (decoratorManager != null) {
            DJInfo djInfo = decoratorManager.getCurrentDJInfo();
            if (djInfo != null && djInfo.isPresent()) {
                bpm = djInfo.bpm();
                confidence = bpm > 0 ? 0.9 : 0; // BPM present implies confidence
            }
        }

        return formatDjStatus(connected, stale, bpm, confidence);
    }

    private String collectEntityCount() {
        var epm = plugin.getEntityPoolManager();
        if (epm == null) return "0/0";
        int active = 0;
        int max = 0;
        for (var zone : plugin.getZoneManager().getAllZones()) {
            active += epm.getEntityCount(zone.getName());
            max += plugin.getConfig().getInt("entities.max-per-zone", 500);
        }
        return formatEntityCount(active, max);
    }

    private String collectActiveZones() {
        var zm = plugin.getZoneManager();
        if (zm == null) return "0";
        return String.valueOf(zm.getAllZones().size());
    }

    private String collectRenderTime() {
        // Render time tracking will be wired in Task 4
        return formatRenderTime(0);
    }

    private String collectLatency() {
        var lt = plugin.getLatencyTracker();
        if (lt == null) return "N/A";
        return String.format("%.0fms", lt.getTotalAvgMs());
    }

    private String collectSequences() {
        var sm = plugin.getSequenceManager();
        return sm != null ? String.valueOf(sm.getActiveCount()) : "0";
    }

    private String collectRecordingStatus() {
        var rm = plugin.getRecordingManager();
        if (rm == null) return null;
        if (rm.isRecording()) return "REC";
        if (rm.isReplaying()) return "PLAY";
        return null;
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
