# Three Features Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement DJ Connection State Indicator, In-Game Performance Metrics, and Multi-Stage Sequencing for the Minecraft plugin.

**Architecture:** Three independent features built in order of size. Each adds new classes under `com.audioviz` and hooks into the existing plugin lifecycle via `AudioVizPlugin.onEnable()`. Pattern switching uses the existing `BitmapPatternManager.setPattern()` API. All features are pure server-side Java — no client changes.

**Tech Stack:** Java 21, Paper API 1.21.1, JUnit 5, Mockito 5, Maven

**Test command:** `cd minecraft_plugin && ./mvnw test -pl .`

**Package:** `com.audioviz` (NOT `com.ryanalexander.audioviz`)

---

## Feature A: DJ Connection State Indicator

### Task 1: ConnectionStateListener — Core Logic

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/connection/ConnectionStateListener.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/connection/ConnectionStateListenerTest.java`

**Step 1: Write the test**

```java
package com.audioviz.connection;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionStateListenerTest {

    @Nested
    @DisplayName("Staleness Detection")
    class StalenessDetection {

        @Test
        @DisplayName("not stale when frame received recently")
        void notStaleWhenRecent() {
            long now = System.currentTimeMillis();
            assertFalse(ConnectionStateListener.isStale(now - 1000, now, 3000));
        }

        @Test
        @DisplayName("stale when no frame for longer than threshold")
        void staleAfterThreshold() {
            long now = System.currentTimeMillis();
            assertTrue(ConnectionStateListener.isStale(now - 4000, now, 3000));
        }

        @Test
        @DisplayName("not stale at exact threshold boundary")
        void notStaleAtExactBoundary() {
            long now = System.currentTimeMillis();
            assertFalse(ConnectionStateListener.isStale(now - 3000, now, 3000));
        }

        @Test
        @DisplayName("stale when lastFrameMs is 0 (never received)")
        void staleWhenNeverReceived() {
            long now = System.currentTimeMillis();
            assertTrue(ConnectionStateListener.isStale(0, now, 3000));
        }
    }

    @Nested
    @DisplayName("Brightness Ramp")
    class BrightnessRamp {

        @Test
        @DisplayName("ramp computes intermediate values")
        void rampIntermediate() {
            double result = ConnectionStateListener.computeRampedBrightness(
                1.0, 0.3, 10, 5);
            // Halfway through ramp: 1.0 + (0.3 - 1.0) * 5/10 = 0.65
            assertEquals(0.65, result, 0.001);
        }

        @Test
        @DisplayName("ramp at start returns current")
        void rampAtStart() {
            double result = ConnectionStateListener.computeRampedBrightness(
                1.0, 0.3, 20, 0);
            assertEquals(1.0, result, 0.001);
        }

        @Test
        @DisplayName("ramp at end returns target")
        void rampAtEnd() {
            double result = ConnectionStateListener.computeRampedBrightness(
                1.0, 0.3, 20, 20);
            assertEquals(0.3, result, 0.001);
        }

        @Test
        @DisplayName("ramp past end clamps to target")
        void rampPastEnd() {
            double result = ConnectionStateListener.computeRampedBrightness(
                1.0, 0.3, 20, 25);
            assertEquals(0.3, result, 0.001);
        }
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd minecraft_plugin && ./mvnw test -Dtest=com.audioviz.connection.ConnectionStateListenerTest -pl . 2>&1 | tail -10`
Expected: FAIL — class not found

**Step 3: Write the implementation**

```java
package com.audioviz.connection;

import com.audioviz.AudioVizPlugin;
import com.audioviz.bitmap.BitmapPatternManager;
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
        startBrightnessRamp(1.0);
        broadcastActionBar(Component.text("DJ connected", NamedTextColor.GREEN));
        spawnConnectionParticles(true);
        logger.info("DJ connected: " + info);
    }

    /** Called by VizWebSocketServer when a DJ client disconnects. */
    public void onDjDisconnect(String reason) {
        djConnected = false;
        broadcastActionBar(Component.text("DJ disconnected", NamedTextColor.RED));
        spawnConnectionParticles(false);
        logger.info("DJ disconnected: " + reason);
    }

    /** Called by BitmapPatternManager when an audio frame is received. */
    public void onAudioFrame() {
        lastFrameMs = System.currentTimeMillis();
        if (stale) {
            stale = false;
            startBrightnessRamp(1.0);
            broadcastActionBar(Component.text("Audio signal restored", NamedTextColor.GREEN));
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
        // currentBrightness stays at whatever the last ramp reached
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
```

**Step 4: Run tests to verify they pass**

Run: `cd minecraft_plugin && ./mvnw test -Dtest=com.audioviz.connection.ConnectionStateListenerTest -pl . 2>&1 | tail -10`
Expected: All 8 tests PASS

**Step 5: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/connection/ConnectionStateListener.java \
        minecraft_plugin/src/test/java/com/audioviz/connection/ConnectionStateListenerTest.java
git commit -m "feat: add ConnectionStateListener with staleness detection and brightness ramp"
```

---

### Task 2: Wire ConnectionStateListener into Plugin

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java`

**Step 1: Add field and init to AudioVizPlugin**

In `AudioVizPlugin.java`, add:

1. Import: `import com.audioviz.connection.ConnectionStateListener;`
2. Field: `private ConnectionStateListener connectionStateListener;`
3. In `onEnable()`, after `this.compositionManager = new CompositionManager();`:
   ```java
   this.connectionStateListener = new ConnectionStateListener(this);
   this.connectionStateListener.start();
   ```
4. In `onDisable()`, before the webSocketServer shutdown block:
   ```java
   if (connectionStateListener != null) {
       connectionStateListener.stop();
   }
   ```
5. Getter: `public ConnectionStateListener getConnectionStateListener() { return connectionStateListener; }`

**Step 2: Fire events from VizWebSocketServer**

In `VizWebSocketServer.java`:

1. In `onOpen(WebSocket conn, ClientHandshake handshake)`, after the existing logging:
   ```java
   var listener = plugin.getConnectionStateListener();
   if (listener != null) {
       listener.onDjConnect(conn.getRemoteSocketAddress().toString());
   }
   ```

2. In `onClose(WebSocket conn, int code, String reason, boolean remote)`, after the existing logging:
   ```java
   var listener = plugin.getConnectionStateListener();
   if (listener != null && clients.isEmpty()) {
       listener.onDjDisconnect(reason != null ? reason : "connection closed");
   }
   ```

**Step 3: Fire audio frame event from BitmapPatternManager**

In `BitmapPatternManager.java`, in the `updateAudioState(AudioState audio)` method, add at the end:
```java
var listener = plugin.getConnectionStateListener();
if (listener != null) {
    listener.onAudioFrame();
}
```

**Step 4: Run all tests**

Run: `cd minecraft_plugin && ./mvnw test -pl . 2>&1 | tail -10`
Expected: All tests PASS (no regressions)

**Step 5: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java \
        minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java \
        minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java
git commit -m "feat: wire ConnectionStateListener into plugin lifecycle and WebSocket events"
```

---

## Feature B: In-Game Performance Metrics

### Task 3: MetricsDisplay — Core Logic

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/metrics/MetricsDisplay.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/metrics/MetricsDisplayTest.java`

**Step 1: Write the test**

```java
package com.audioviz.metrics;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class MetricsDisplayTest {

    @Nested
    @DisplayName("Metric Formatting")
    class Formatting {

        @Test
        @DisplayName("formatRenderTime rounds to 1 decimal")
        void formatRenderTime() {
            assertEquals("4.2ms", MetricsDisplay.formatRenderTime(4.23456));
        }

        @Test
        @DisplayName("formatRenderTime handles zero")
        void formatRenderTimeZero() {
            assertEquals("0.0ms", MetricsDisplay.formatRenderTime(0.0));
        }

        @Test
        @DisplayName("formatEntityCount shows used/total")
        void formatEntityCount() {
            assertEquals("312/500", MetricsDisplay.formatEntityCount(312, 500));
        }

        @Test
        @DisplayName("formatBpm shows integer when confident")
        void formatBpmConfident() {
            assertEquals("128 BPM", MetricsDisplay.formatBpm(128.4, 0.8));
        }

        @Test
        @DisplayName("formatBpm shows dash when not confident")
        void formatBpmNotConfident() {
            assertEquals("-- BPM", MetricsDisplay.formatBpm(128.4, 0.3));
        }

        @Test
        @DisplayName("formatDjStatus connected")
        void formatDjConnected() {
            assertEquals("Connected (128 BPM)",
                MetricsDisplay.formatDjStatus(true, false, 128.0, 0.9));
        }

        @Test
        @DisplayName("formatDjStatus disconnected")
        void formatDjDisconnected() {
            assertEquals("Disconnected",
                MetricsDisplay.formatDjStatus(false, false, 0, 0));
        }

        @Test
        @DisplayName("formatDjStatus stale")
        void formatDjStale() {
            assertEquals("Signal Lost",
                MetricsDisplay.formatDjStatus(true, true, 128.0, 0.9));
        }
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd minecraft_plugin && ./mvnw test -Dtest=com.audioviz.metrics.MetricsDisplayTest -pl . 2>&1 | tail -10`
Expected: FAIL — class not found

**Step 3: Write the implementation**

```java
package com.audioviz.metrics;

import com.audioviz.AudioVizPlugin;
import com.audioviz.connection.ConnectionStateListener;
import com.audioviz.patterns.AudioState;
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
    private static final long UPDATE_INTERVAL_TICKS = 20L; // 1 second

    public MetricsDisplay(AudioVizPlugin plugin) {
        this.plugin = plugin;
    }

    /** Start the update loop. */
    public void start() {
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::update, 20L, UPDATE_INTERVAL_TICKS);
    }

    /** Stop the update loop and clean up scoreboards. */
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

    /** Toggle metrics display for a player. Returns true if now showing. */
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

    /** Check if a player has metrics enabled. */
    public boolean isActive(UUID uuid) {
        return activeViewers.contains(uuid);
    }

    // ========== Update Loop ==========

    private void update() {
        if (activeViewers.isEmpty()) return;

        // Collect metrics once
        String djStatus = collectDjStatus();
        String entities = collectEntityCount();
        String activeZones = collectActiveZones();
        String renderTime = collectRenderTime();
        String sequences = collectSequences();

        // Update each viewer's scoreboard
        for (UUID uuid : new ArrayList<>(activeViewers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                activeViewers.remove(uuid);
                playerScoreboards.remove(uuid);
                continue;
            }
            updateScoreboard(player, djStatus, entities, activeZones, renderTime, sequences);
        }
    }

    private void updateScoreboard(Player player, String djStatus, String entities,
                                   String activeZones, String renderTime, String sequences) {
        Scoreboard board = playerScoreboards.computeIfAbsent(player.getUniqueId(), k -> {
            Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
            player.setScoreboard(sb);
            return sb;
        });

        // Re-create objective each update for clean lines
        Objective existing = board.getObjective(OBJECTIVE_NAME);
        if (existing != null) existing.unregister();

        Objective obj = board.registerNewObjective(OBJECTIVE_NAME,
            Criteria.DUMMY, Component.text("MCAV Metrics", NamedTextColor.AQUA));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Lines (higher score = higher position)
        obj.getScore("DJ: " + djStatus).setScore(6);
        obj.getScore("Entities: " + entities).setScore(5);
        obj.getScore("Zones: " + activeZones).setScore(4);
        obj.getScore("Render: " + renderTime).setScore(3);
        obj.getScore("Sequences: " + sequences).setScore(2);
    }

    private void removeScoreboard(Player player) {
        Scoreboard board = playerScoreboards.get(player.getUniqueId());
        if (board != null) {
            Objective obj = board.getObjective(OBJECTIVE_NAME);
            if (obj != null) obj.unregister();
        }
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    // ========== Metric Collectors ==========

    private String collectDjStatus() {
        ConnectionStateListener conn = plugin.getConnectionStateListener();
        AudioState audio = plugin.getBitmapPatternManager() != null
            ? plugin.getBitmapPatternManager().getLatestAudioState() : null;
        boolean connected = conn != null && conn.isDjConnected();
        boolean stale = conn != null && conn.isAudioStale();
        double bpm = audio != null ? audio.getBeatPhase() : 0; // BPM from audio state
        double confidence = audio != null ? audio.getTempoConfidence() : 0;
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
        var bpm = plugin.getBitmapPatternManager();
        return bpm != null ? String.valueOf(bpm.getActiveZoneCount()) : "0";
    }

    private String collectRenderTime() {
        var bpm = plugin.getBitmapPatternManager();
        double avgMs = bpm != null ? bpm.getAvgRenderTimeMs() : 0;
        return formatRenderTime(avgMs);
    }

    private String collectSequences() {
        // Will be wired to SequenceManager in Feature C
        return "0";
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
```

**Step 4: Run tests**

Run: `cd minecraft_plugin && ./mvnw test -Dtest=com.audioviz.metrics.MetricsDisplayTest -pl . 2>&1 | tail -10`
Expected: All 8 tests PASS

**Step 5: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/metrics/MetricsDisplay.java \
        minecraft_plugin/src/test/java/com/audioviz/metrics/MetricsDisplayTest.java
git commit -m "feat: add MetricsDisplay with scoreboard sidebar and metric formatting"
```

---

### Task 4: Wire MetricsDisplay into Plugin and Commands

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/commands/AudioVizCommand.java`

**Step 1: Add to AudioVizPlugin**

1. Import: `import com.audioviz.metrics.MetricsDisplay;`
2. Field: `private MetricsDisplay metricsDisplay;`
3. In `onEnable()`, after the connectionStateListener init:
   ```java
   this.metricsDisplay = new MetricsDisplay(this);
   this.metricsDisplay.start();
   ```
4. In `onDisable()`, before connectionStateListener stop:
   ```java
   if (metricsDisplay != null) {
       metricsDisplay.stop();
   }
   ```
5. Getter: `public MetricsDisplay getMetricsDisplay() { return metricsDisplay; }`

**Step 2: Add /av metrics command to AudioVizCommand**

In `onCommand()`, add a new case in the main switch:
```java
case "metrics" -> handleMetricsCommand(sender);
```

Add the handler method:
```java
private void handleMetricsCommand(CommandSender sender) {
    if (!(sender instanceof Player player)) {
        sender.sendMessage(ChatColor.RED + "Only players can use this command.");
        return;
    }
    var display = plugin.getMetricsDisplay();
    if (display == null) {
        sender.sendMessage(ChatColor.RED + "Metrics display not available.");
        return;
    }
    boolean nowShowing = display.toggle(player);
    sender.sendMessage(ChatColor.AQUA + "Metrics display " +
        (nowShowing ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled"));
}
```

In `onTabComplete()`, add `"metrics"` to the top-level subcommand list.

In `sendHelp()`, add: `sender.sendMessage(ChatColor.AQUA + "/av metrics" + ChatColor.GRAY + " - Toggle performance metrics sidebar");`

**Step 3: Run all tests**

Run: `cd minecraft_plugin && ./mvnw test -pl . 2>&1 | tail -10`
Expected: All tests PASS

**Step 4: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java \
        minecraft_plugin/src/main/java/com/audioviz/commands/AudioVizCommand.java
git commit -m "feat: wire MetricsDisplay into plugin and add /av metrics command"
```

---

## Feature C: Multi-Stage Sequencing

### Task 5: Data Models — PlaybackMode, SequenceStep, Sequence

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/sequence/PlaybackMode.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/sequence/SequenceStep.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/sequence/Sequence.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/sequence/SequenceTest.java`

**Step 1: Write the test**

```java
package com.audioviz.sequence;

import org.junit.jupiter.api.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SequenceTest {

    @Nested
    @DisplayName("SequenceStep")
    class StepTests {
        @Test
        void storesPatternsAndDuration() {
            var step = new SequenceStep(
                Map.of("zone1", "bmp_plasma", "zone2", "bmp_fire"),
                200, "dissolve", 40);
            assertEquals(2, step.zonePatterns().size());
            assertEquals("bmp_plasma", step.zonePatterns().get("zone1"));
            assertEquals(200, step.durationTicks());
            assertEquals("dissolve", step.transitionId());
            assertEquals(40, step.transitionDuration());
        }

        @Test
        void zeroMeansUseDefault() {
            var step = new SequenceStep(Map.of("zone1", "bmp_bars"), 0, null, 0);
            assertEquals(0, step.durationTicks());
            assertNull(step.transitionId());
        }
    }

    @Nested
    @DisplayName("Sequence")
    class SequenceTests {
        @Test
        void constructorAndDefaults() {
            var seq = new Sequence("my_sequence");
            assertEquals("my_sequence", seq.getName());
            assertEquals(PlaybackMode.LOOP, seq.getMode());
            assertTrue(seq.getSteps().isEmpty());
            assertEquals(600, seq.getDefaultStepDuration());
            assertEquals("crossfade", seq.getDefaultTransition());
            assertEquals(20, seq.getDefaultTransitionDuration());
        }

        @Test
        void addAndRemoveSteps() {
            var seq = new Sequence("test");
            var step = new SequenceStep(Map.of("z", "p"), 100, null, 0);
            seq.addStep(step);
            assertEquals(1, seq.getSteps().size());
            seq.removeStep(0);
            assertTrue(seq.getSteps().isEmpty());
        }

        @Test
        void effectiveDurationUsesDefault() {
            var seq = new Sequence("test");
            seq.setDefaultStepDuration(400);
            var step = new SequenceStep(Map.of("z", "p"), 0, null, 0);
            assertEquals(400, seq.getEffectiveDuration(step));
        }

        @Test
        void effectiveDurationUsesOverride() {
            var seq = new Sequence("test");
            seq.setDefaultStepDuration(400);
            var step = new SequenceStep(Map.of("z", "p"), 200, null, 0);
            assertEquals(200, seq.getEffectiveDuration(step));
        }

        @Test
        void effectiveTransitionUsesDefault() {
            var seq = new Sequence("test");
            var step = new SequenceStep(Map.of("z", "p"), 0, null, 0);
            assertEquals("crossfade", seq.getEffectiveTransition(step));
        }

        @Test
        void effectiveTransitionUsesOverride() {
            var seq = new Sequence("test");
            var step = new SequenceStep(Map.of("z", "p"), 0, "wipe_left", 0);
            assertEquals("wipe_left", seq.getEffectiveTransition(step));
        }
    }

    @Nested
    @DisplayName("PlaybackMode")
    class PlaybackModeTests {
        @Test
        void allModesExist() {
            assertEquals(3, PlaybackMode.values().length);
            assertNotNull(PlaybackMode.LOOP);
            assertNotNull(PlaybackMode.SHUFFLE);
            assertNotNull(PlaybackMode.ONCE);
        }
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd minecraft_plugin && ./mvnw test -Dtest=com.audioviz.sequence.SequenceTest -pl . 2>&1 | tail -10`
Expected: FAIL

**Step 3: Write the implementations**

`PlaybackMode.java`:
```java
package com.audioviz.sequence;

public enum PlaybackMode {
    LOOP,
    SHUFFLE,
    ONCE
}
```

`SequenceStep.java`:
```java
package com.audioviz.sequence;

import java.util.Map;

/**
 * A single step in a sequence — assigns patterns to zones for a duration.
 *
 * @param zonePatterns     map of zoneName -> patternId
 * @param durationTicks    step duration (0 = use sequence default)
 * @param transitionId     transition type (null = use sequence default)
 * @param transitionDuration transition duration in ticks (0 = use sequence default)
 */
public record SequenceStep(
    Map<String, String> zonePatterns,
    int durationTicks,
    String transitionId,
    int transitionDuration
) {}
```

`Sequence.java`:
```java
package com.audioviz.sequence;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered list of steps that rotate patterns across zones.
 */
public class Sequence {

    private final String name;
    private final List<SequenceStep> steps = new ArrayList<>();
    private PlaybackMode mode = PlaybackMode.LOOP;
    private int defaultStepDuration = 600;       // 30 seconds at 20 TPS
    private String defaultTransition = "crossfade";
    private int defaultTransitionDuration = 20;   // 1 second

    public Sequence(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public List<SequenceStep> getSteps() { return steps; }
    public PlaybackMode getMode() { return mode; }
    public int getDefaultStepDuration() { return defaultStepDuration; }
    public String getDefaultTransition() { return defaultTransition; }
    public int getDefaultTransitionDuration() { return defaultTransitionDuration; }

    public void setMode(PlaybackMode mode) { this.mode = mode; }
    public void setDefaultStepDuration(int ticks) { this.defaultStepDuration = Math.max(1, ticks); }
    public void setDefaultTransition(String id) { this.defaultTransition = id; }
    public void setDefaultTransitionDuration(int ticks) { this.defaultTransitionDuration = Math.max(1, ticks); }

    public void addStep(SequenceStep step) { steps.add(step); }
    public void removeStep(int index) { steps.remove(index); }

    /** Get effective duration for a step (step override or sequence default). */
    public int getEffectiveDuration(SequenceStep step) {
        return step.durationTicks() > 0 ? step.durationTicks() : defaultStepDuration;
    }

    /** Get effective transition for a step (step override or sequence default). */
    public String getEffectiveTransition(SequenceStep step) {
        return step.transitionId() != null ? step.transitionId() : defaultTransition;
    }

    /** Get effective transition duration for a step. */
    public int getEffectiveTransitionDuration(SequenceStep step) {
        return step.transitionDuration() > 0 ? step.transitionDuration() : defaultTransitionDuration;
    }
}
```

**Step 4: Run tests**

Run: `cd minecraft_plugin && ./mvnw test -Dtest=com.audioviz.sequence.SequenceTest -pl . 2>&1 | tail -10`
Expected: All 9 tests PASS

**Step 5: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/sequence/PlaybackMode.java \
        minecraft_plugin/src/main/java/com/audioviz/sequence/SequenceStep.java \
        minecraft_plugin/src/main/java/com/audioviz/sequence/Sequence.java \
        minecraft_plugin/src/test/java/com/audioviz/sequence/SequenceTest.java
git commit -m "feat: add Sequence data models — PlaybackMode, SequenceStep, Sequence"
```

---

### Task 6: SequencePlayer — Playback Engine

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/sequence/SequencePlayer.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/sequence/SequencePlayerTest.java`

**Step 1: Write the test**

```java
package com.audioviz.sequence;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SequencePlayerTest {

    private Sequence makeSequence(int stepCount, int durationPerStep) {
        Sequence seq = new Sequence("test");
        seq.setDefaultStepDuration(durationPerStep);
        for (int i = 0; i < stepCount; i++) {
            seq.addStep(new SequenceStep(
                Map.of("zone1", "pattern_" + i), 0, null, 0));
        }
        return seq;
    }

    @Nested
    @DisplayName("Step Advancement")
    class StepAdvancement {

        @Test
        @DisplayName("starts at step 0")
        void startsAtZero() {
            var player = new SequencePlayer(makeSequence(3, 100));
            assertEquals(0, player.getCurrentStepIndex());
        }

        @Test
        @DisplayName("advances after duration expires")
        void advancesOnExpiry() {
            var player = new SequencePlayer(makeSequence(3, 10));
            List<SequencePlayer.StepTransition> transitions = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                var t = player.tick();
                if (t != null) transitions.add(t);
            }
            assertEquals(1, player.getCurrentStepIndex());
            assertEquals(1, transitions.size());
        }

        @Test
        @DisplayName("reports pattern changes in transition")
        void reportsPatterns() {
            var player = new SequencePlayer(makeSequence(3, 5));
            SequencePlayer.StepTransition t = null;
            for (int i = 0; i < 5; i++) {
                var result = player.tick();
                if (result != null) t = result;
            }
            assertNotNull(t);
            assertEquals("pattern_1", t.zonePatterns().get("zone1"));
        }
    }

    @Nested
    @DisplayName("Loop Mode")
    class LoopMode {

        @Test
        @DisplayName("loops back to step 0 after last step")
        void loopsToStart() {
            var seq = makeSequence(2, 5);
            seq.setMode(PlaybackMode.LOOP);
            var player = new SequencePlayer(seq);

            // Tick through both steps (5 + 5 = 10 ticks)
            for (int i = 0; i < 10; i++) player.tick();
            assertEquals(0, player.getCurrentStepIndex());
            assertFalse(player.isFinished());
        }
    }

    @Nested
    @DisplayName("Once Mode")
    class OnceMode {

        @Test
        @DisplayName("stops after last step")
        void stopsAtEnd() {
            var seq = makeSequence(2, 5);
            seq.setMode(PlaybackMode.ONCE);
            var player = new SequencePlayer(seq);

            for (int i = 0; i < 10; i++) player.tick();
            assertTrue(player.isFinished());
        }
    }

    @Nested
    @DisplayName("Shuffle Mode")
    class ShuffleMode {

        @Test
        @DisplayName("visits all steps before repeating")
        void visitsAll() {
            var seq = makeSequence(4, 5);
            seq.setMode(PlaybackMode.SHUFFLE);
            var player = new SequencePlayer(seq);

            Set<Integer> visited = new HashSet<>();
            visited.add(player.getCurrentStepIndex());
            // Tick through 4 steps
            for (int i = 0; i < 20; i++) {
                player.tick();
                visited.add(player.getCurrentStepIndex());
            }
            // Should have visited all 4 steps
            assertEquals(4, visited.size());
        }
    }

    @Nested
    @DisplayName("Skip")
    class Skip {

        @Test
        @DisplayName("skip advances immediately")
        void skipAdvances() {
            var player = new SequencePlayer(makeSequence(3, 100));
            assertEquals(0, player.getCurrentStepIndex());
            var t = player.skip();
            assertNotNull(t);
            assertEquals(1, player.getCurrentStepIndex());
        }
    }

    @Nested
    @DisplayName("Empty Sequence")
    class EmptySequence {

        @Test
        @DisplayName("empty sequence is immediately finished")
        void emptyFinishes() {
            var player = new SequencePlayer(new Sequence("empty"));
            assertTrue(player.isFinished());
        }
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd minecraft_plugin && ./mvnw test -Dtest=com.audioviz.sequence.SequencePlayerTest -pl . 2>&1 | tail -10`
Expected: FAIL

**Step 3: Write the implementation**

```java
package com.audioviz.sequence;

import java.util.*;

/**
 * Plays through a Sequence's steps, tracking duration and advancing.
 * Pure logic — no Bukkit dependencies. Call tick() once per server tick.
 */
public class SequencePlayer {

    private final Sequence sequence;
    private int currentStepIndex;
    private int ticksInCurrentStep;
    private boolean finished;

    // Shuffle state
    private final List<Integer> shuffleOrder = new ArrayList<>();
    private int shufflePosition = 0;
    private final Random random = new Random();

    public SequencePlayer(Sequence sequence) {
        this.sequence = sequence;
        this.currentStepIndex = 0;
        this.ticksInCurrentStep = 0;
        this.finished = sequence.getSteps().isEmpty();

        if (sequence.getMode() == PlaybackMode.SHUFFLE && !sequence.getSteps().isEmpty()) {
            buildShuffleOrder(-1);
            this.currentStepIndex = shuffleOrder.get(0);
        }
    }

    /**
     * Advance one tick. Returns a StepTransition if the step changed, null otherwise.
     */
    public StepTransition tick() {
        if (finished) return null;

        ticksInCurrentStep++;
        SequenceStep currentStep = sequence.getSteps().get(currentStepIndex);
        int duration = sequence.getEffectiveDuration(currentStep);

        if (ticksInCurrentStep >= duration) {
            return advance();
        }
        return null;
    }

    /**
     * Skip to next step immediately.
     */
    public StepTransition skip() {
        if (finished) return null;
        return advance();
    }

    private StepTransition advance() {
        int nextIndex = computeNextIndex();
        if (nextIndex < 0) {
            finished = true;
            return null;
        }

        currentStepIndex = nextIndex;
        ticksInCurrentStep = 0;

        SequenceStep step = sequence.getSteps().get(currentStepIndex);
        return new StepTransition(
            step.zonePatterns(),
            sequence.getEffectiveTransition(step),
            sequence.getEffectiveTransitionDuration(step)
        );
    }

    private int computeNextIndex() {
        int stepCount = sequence.getSteps().size();
        return switch (sequence.getMode()) {
            case LOOP -> (currentStepIndex + 1) % stepCount;
            case ONCE -> {
                int next = currentStepIndex + 1;
                yield next < stepCount ? next : -1;
            }
            case SHUFFLE -> {
                shufflePosition++;
                if (shufflePosition >= shuffleOrder.size()) {
                    buildShuffleOrder(currentStepIndex);
                    shufflePosition = 0;
                }
                yield shuffleOrder.get(shufflePosition);
            }
        };
    }

    private void buildShuffleOrder(int lastIndex) {
        shuffleOrder.clear();
        for (int i = 0; i < sequence.getSteps().size(); i++) {
            shuffleOrder.add(i);
        }
        Collections.shuffle(shuffleOrder, random);
        // Avoid immediate repeat of last step
        if (lastIndex >= 0 && !shuffleOrder.isEmpty() && shuffleOrder.get(0) == lastIndex) {
            // Swap first with a random other position
            if (shuffleOrder.size() > 1) {
                int swapIdx = 1 + random.nextInt(shuffleOrder.size() - 1);
                Collections.swap(shuffleOrder, 0, swapIdx);
            }
        }
    }

    public int getCurrentStepIndex() { return currentStepIndex; }
    public boolean isFinished() { return finished; }
    public Sequence getSequence() { return sequence; }
    public int getTicksInCurrentStep() { return ticksInCurrentStep; }

    /**
     * Returned when a step transition occurs.
     */
    public record StepTransition(
        Map<String, String> zonePatterns,
        String transitionId,
        int transitionDuration
    ) {}
}
```

**Step 4: Run tests**

Run: `cd minecraft_plugin && ./mvnw test -Dtest=com.audioviz.sequence.SequencePlayerTest -pl . 2>&1 | tail -10`
Expected: All 8 tests PASS

**Step 5: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/sequence/SequencePlayer.java \
        minecraft_plugin/src/test/java/com/audioviz/sequence/SequencePlayerTest.java
git commit -m "feat: add SequencePlayer — playback engine with loop, shuffle, once modes"
```

---

### Task 7: SequenceManager — CRUD, Persistence, Tick Integration

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/sequence/SequenceManager.java`

**Step 1: Write the implementation**

```java
package com.audioviz.sequence;

import com.audioviz.AudioVizPlugin;
import com.audioviz.bitmap.BitmapPatternManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages sequence CRUD, persistence, and active playback.
 */
public class SequenceManager {

    private final AudioVizPlugin plugin;
    private final Map<String, Sequence> sequences = new LinkedHashMap<>();
    private final Map<String, SequencePlayer> activePlayers = new ConcurrentHashMap<>();
    private final File sequencesFile;
    private BukkitTask tickTask;

    public SequenceManager(AudioVizPlugin plugin) {
        this.plugin = plugin;
        this.sequencesFile = new File(plugin.getDataFolder(), "sequences.yml");
    }

    /** Start the tick loop. */
    public void start() {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Stop playback and tick loop. */
    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        activePlayers.clear();
    }

    // ========== Playback ==========

    /** Start a sequence on a named slot (e.g. stage name). */
    public boolean startSequence(String sequenceName, String slotName) {
        Sequence seq = sequences.get(sequenceName);
        if (seq == null || seq.getSteps().isEmpty()) return false;

        var player = new SequencePlayer(seq);
        activePlayers.put(slotName, player);

        // Apply first step immediately
        SequenceStep firstStep = seq.getSteps().get(player.getCurrentStepIndex());
        applyStep(firstStep, seq);
        return true;
    }

    /** Stop a running sequence. */
    public void stopSequence(String slotName) {
        activePlayers.remove(slotName);
    }

    /** Skip to next step. */
    public SequencePlayer.StepTransition skipStep(String slotName) {
        var player = activePlayers.get(slotName);
        if (player == null) return null;
        var transition = player.skip();
        if (transition != null) {
            applyTransition(transition);
        }
        if (player.isFinished()) {
            activePlayers.remove(slotName);
        }
        return transition;
    }

    /** Check if a slot has an active sequence. */
    public boolean isPlaying(String slotName) {
        return activePlayers.containsKey(slotName);
    }

    /** Get active player count (for metrics). */
    public int getActiveCount() {
        return activePlayers.size();
    }

    // ========== Tick ==========

    private void tick() {
        for (var entry : new ArrayList<>(activePlayers.entrySet())) {
            var player = entry.getValue();
            var transition = player.tick();
            if (transition != null) {
                applyTransition(transition);
            }
            if (player.isFinished()) {
                activePlayers.remove(entry.getKey());
            }
        }
    }

    private void applyStep(SequenceStep step, Sequence seq) {
        BitmapPatternManager bpm = plugin.getBitmapPatternManager();
        if (bpm == null) return;
        for (var entry : step.zonePatterns().entrySet()) {
            bpm.setPattern(entry.getKey(), entry.getValue());
        }
    }

    private void applyTransition(SequencePlayer.StepTransition transition) {
        BitmapPatternManager bpm = plugin.getBitmapPatternManager();
        if (bpm == null) return;
        for (var entry : transition.zonePatterns().entrySet()) {
            bpm.setPattern(entry.getKey(), entry.getValue(),
                transition.transitionId(), transition.transitionDuration());
        }
    }

    // ========== CRUD ==========

    public void addSequence(Sequence sequence) {
        sequences.put(sequence.getName(), sequence);
    }

    public Sequence getSequence(String name) {
        return sequences.get(name);
    }

    public void removeSequence(String name) {
        sequences.remove(name);
        activePlayers.remove(name);
    }

    public Collection<String> getSequenceNames() {
        return sequences.keySet();
    }

    // ========== Persistence ==========

    public void loadSequences() {
        if (!sequencesFile.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(sequencesFile);
        sequences.clear();

        for (String name : config.getKeys(false)) {
            var section = config.getConfigurationSection(name);
            if (section == null) continue;

            Sequence seq = new Sequence(name);
            seq.setMode(PlaybackMode.valueOf(
                section.getString("mode", "LOOP").toUpperCase()));
            seq.setDefaultStepDuration(section.getInt("default_step_duration", 600));
            seq.setDefaultTransition(section.getString("default_transition", "crossfade"));
            seq.setDefaultTransitionDuration(section.getInt("default_transition_duration", 20));

            var stepsList = section.getMapList("steps");
            for (var stepMap : stepsList) {
                @SuppressWarnings("unchecked")
                Map<String, String> patterns = (Map<String, String>) stepMap.get("patterns");
                int duration = stepMap.containsKey("duration") ?
                    ((Number) stepMap.get("duration")).intValue() : 0;
                String transId = (String) stepMap.get("transition");
                int transDuration = stepMap.containsKey("transition_duration") ?
                    ((Number) stepMap.get("transition_duration")).intValue() : 0;
                seq.addStep(new SequenceStep(
                    patterns != null ? patterns : Map.of(),
                    duration, transId, transDuration));
            }
            sequences.put(name, seq);
        }
        plugin.getLogger().info("Loaded " + sequences.size() + " sequences");
    }

    public void saveSequences() {
        YamlConfiguration config = new YamlConfiguration();
        for (var seq : sequences.values()) {
            var section = config.createSection(seq.getName());
            section.set("mode", seq.getMode().name());
            section.set("default_step_duration", seq.getDefaultStepDuration());
            section.set("default_transition", seq.getDefaultTransition());
            section.set("default_transition_duration", seq.getDefaultTransitionDuration());

            List<Map<String, Object>> stepsList = new ArrayList<>();
            for (var step : seq.getSteps()) {
                Map<String, Object> stepMap = new LinkedHashMap<>();
                stepMap.put("patterns", new LinkedHashMap<>(step.zonePatterns()));
                if (step.durationTicks() > 0) stepMap.put("duration", step.durationTicks());
                if (step.transitionId() != null) stepMap.put("transition", step.transitionId());
                if (step.transitionDuration() > 0) stepMap.put("transition_duration", step.transitionDuration());
                stepsList.add(stepMap);
            }
            section.set("steps", stepsList);
        }
        try {
            config.save(sequencesFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save sequences", e);
        }
    }

    public void reloadSequences() {
        activePlayers.clear();
        loadSequences();
    }
}
```

**Step 2: Run all tests**

Run: `cd minecraft_plugin && ./mvnw test -pl . 2>&1 | tail -10`
Expected: All tests PASS

**Step 3: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/sequence/SequenceManager.java
git commit -m "feat: add SequenceManager — CRUD, YAML persistence, playback integration"
```

---

### Task 8: Wire SequenceManager into Plugin and Commands

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/commands/AudioVizCommand.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/metrics/MetricsDisplay.java`

**Step 1: Add to AudioVizPlugin**

1. Import: `import com.audioviz.sequence.SequenceManager;`
2. Field: `private SequenceManager sequenceManager;`
3. In `onEnable()`, after metricsDisplay init:
   ```java
   this.sequenceManager = new SequenceManager(this);
   this.sequenceManager.loadSequences();
   this.sequenceManager.start();
   ```
4. In `onDisable()`, before metricsDisplay stop:
   ```java
   if (sequenceManager != null) {
       sequenceManager.stop();
       sequenceManager.saveSequences();
   }
   ```
5. Getter: `public SequenceManager getSequenceManager() { return sequenceManager; }`

**Step 2: Add /av sequence commands to AudioVizCommand**

In `onCommand()`, add case:
```java
case "sequence", "seq" -> handleSequenceCommand(sender, args);
```

Add the handler:
```java
private void handleSequenceCommand(CommandSender sender, String[] args) {
    var sm = plugin.getSequenceManager();
    if (sm == null) {
        sender.sendMessage(ChatColor.RED + "Sequence manager not available.");
        return;
    }
    if (args.length < 2) {
        sender.sendMessage(ChatColor.AQUA + "Usage: /av sequence <start|stop|skip|list|reload>");
        return;
    }
    switch (args[1].toLowerCase()) {
        case "start" -> {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /av sequence start <name> [slot]");
                return;
            }
            String seqName = args[2];
            String slot = args.length > 3 ? args[3] : "default";
            if (sm.startSequence(seqName, slot)) {
                sender.sendMessage(ChatColor.GREEN + "Started sequence '" + seqName + "' on slot '" + slot + "'");
            } else {
                sender.sendMessage(ChatColor.RED + "Sequence '" + seqName + "' not found or empty.");
            }
        }
        case "stop" -> {
            String slot = args.length > 2 ? args[2] : "default";
            sm.stopSequence(slot);
            sender.sendMessage(ChatColor.GREEN + "Stopped sequence on slot '" + slot + "'");
        }
        case "skip" -> {
            String slot = args.length > 2 ? args[2] : "default";
            var t = sm.skipStep(slot);
            if (t != null) {
                sender.sendMessage(ChatColor.GREEN + "Skipped to next step");
            } else {
                sender.sendMessage(ChatColor.RED + "No active sequence on slot '" + slot + "'");
            }
        }
        case "list" -> {
            var names = sm.getSequenceNames();
            if (names.isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + "No sequences defined. Edit sequences.yml to create one.");
            } else {
                sender.sendMessage(ChatColor.AQUA + "Sequences (" + names.size() + "):");
                for (var name : names) {
                    var seq = sm.getSequence(name);
                    String playing = sm.isPlaying(name) ? ChatColor.GREEN + " [PLAYING]" : "";
                    sender.sendMessage(ChatColor.GRAY + "  " + name +
                        " (" + seq.getSteps().size() + " steps, " + seq.getMode() + ")" + playing);
                }
            }
        }
        case "reload" -> {
            sm.reloadSequences();
            sender.sendMessage(ChatColor.GREEN + "Reloaded " + sm.getSequenceNames().size() + " sequences");
        }
        default -> sender.sendMessage(ChatColor.RED + "Unknown: /av sequence " + args[1]);
    }
}
```

In `onTabComplete()`, add `"sequence"` and `"seq"` to top-level, with sub-completions for start/stop/skip/list/reload and sequence names for start.

In `sendHelp()`, add:
```java
sender.sendMessage(ChatColor.AQUA + "/av sequence <start|stop|skip|list|reload>" + ChatColor.GRAY + " - Pattern sequencing");
```

**Step 3: Wire metrics display to sequence count**

In `MetricsDisplay.java`, update `collectSequences()`:
```java
private String collectSequences() {
    var sm = plugin.getSequenceManager();
    return sm != null ? String.valueOf(sm.getActiveCount()) : "0";
}
```

**Step 4: Run all tests**

Run: `cd minecraft_plugin && ./mvnw test -pl . 2>&1 | tail -10`
Expected: All tests PASS

**Step 5: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java \
        minecraft_plugin/src/main/java/com/audioviz/commands/AudioVizCommand.java \
        minecraft_plugin/src/main/java/com/audioviz/metrics/MetricsDisplay.java
git commit -m "feat: wire SequenceManager into plugin, add /av sequence commands, connect metrics"
```

---

### Task 9: Run Full Test Suite and Verify

**Step 1: Run all tests**

Run: `cd minecraft_plugin && ./mvnw test -pl . 2>&1 | tail -30`
Expected: All tests PASS, 0 failures

**Step 2: Verify new test count**

Run: `cd minecraft_plugin && ./mvnw test -pl . 2>&1 | grep "Tests run"`
Expected: Test count increased by ~25 (ConnectionStateListener: 8, MetricsDisplay: 8, Sequence: 9, SequencePlayer: 8)

**Step 3: Final commit if needed**

```bash
git add -A minecraft_plugin/
git commit -m "feat: three features complete — connection state, metrics, sequencing"
```
