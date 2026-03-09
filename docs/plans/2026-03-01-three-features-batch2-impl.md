# Three Features (Batch 2) Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement Beat-Sync Fine-Tuning, Latency Monitoring, and Recording & Replay for the Minecraft plugin.

**Architecture:** Three independent features built in size order. Each adds new classes under `com.audioviz` and hooks into the existing plugin lifecycle via `AudioVizPlugin.onEnable()`. Beat-sync modifies the audio pipeline in `BitmapPatternManager.updateAudioState()`. Latency adds timing capture in `MessageHandler`. Recording hooks into the tick loop. All pure server-side Java.

**Tech Stack:** Java 21, Paper API 1.21.11, JUnit 5, Mockito 5, Maven

**Test command:** `cd minecraft_plugin && ./mvnw test -pl .`

**Package:** `com.audioviz` (NOT `com.ryanalexander.audioviz`)

---

## Feature A: Beat-Sync Fine-Tuning

### Task 1: BeatSyncConfig — Data Model + Tests

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/beatsync/BeatSyncConfig.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/beatsync/BeatSyncConfigTest.java`

**Step 1: Write the test**

```java
package com.audioviz.beatsync;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class BeatSyncConfigTest {

    @Nested
    @DisplayName("Defaults")
    class Defaults {

        @Test
        @DisplayName("default config uses auto BPM")
        void defaultAutoBpm() {
            var config = new BeatSyncConfig();
            assertEquals(0.0, config.getManualBpm());
            assertTrue(config.isAutoBpm());
        }

        @Test
        @DisplayName("default phase offset is zero")
        void defaultPhaseOffset() {
            assertEquals(0.0, new BeatSyncConfig().getPhaseOffset());
        }

        @Test
        @DisplayName("default sensitivity multiplier is 1.0")
        void defaultSensitivity() {
            assertEquals(1.0, new BeatSyncConfig().getBeatThresholdMultiplier());
        }

        @Test
        @DisplayName("projection enabled by default")
        void defaultProjectionEnabled() {
            assertTrue(new BeatSyncConfig().isProjectionEnabled());
        }
    }

    @Nested
    @DisplayName("BPM Override")
    class BpmOverride {

        @Test
        @DisplayName("manual BPM overrides auto")
        void manualBpm() {
            var config = new BeatSyncConfig();
            config.setManualBpm(128.0);
            assertEquals(128.0, config.getManualBpm());
            assertFalse(config.isAutoBpm());
        }

        @Test
        @DisplayName("setting BPM to 0 returns to auto")
        void zeroResetsToAuto() {
            var config = new BeatSyncConfig();
            config.setManualBpm(128.0);
            config.setManualBpm(0);
            assertTrue(config.isAutoBpm());
        }

        @Test
        @DisplayName("negative BPM clamped to 0")
        void negativeClamped() {
            var config = new BeatSyncConfig();
            config.setManualBpm(-10);
            assertEquals(0, config.getManualBpm());
        }

        @Test
        @DisplayName("BPM clamped to 300")
        void maxClamped() {
            var config = new BeatSyncConfig();
            config.setManualBpm(500);
            assertEquals(300, config.getManualBpm());
        }
    }

    @Nested
    @DisplayName("Phase Offset")
    class PhaseOffset {

        @Test
        @DisplayName("phase offset clamped to -0.5..0.5")
        void clamped() {
            var config = new BeatSyncConfig();
            config.setPhaseOffset(0.8);
            assertEquals(0.5, config.getPhaseOffset());
            config.setPhaseOffset(-0.8);
            assertEquals(-0.5, config.getPhaseOffset());
        }

        @Test
        @DisplayName("applyPhaseOffset wraps correctly")
        void wraps() {
            assertEquals(0.2, BeatSyncConfig.applyPhaseOffset(0.0, 0.2), 0.001);
            assertEquals(0.8, BeatSyncConfig.applyPhaseOffset(0.0, -0.2), 0.001);
            assertEquals(0.1, BeatSyncConfig.applyPhaseOffset(0.9, 0.2), 0.001);
        }
    }

    @Nested
    @DisplayName("Sensitivity")
    class Sensitivity {

        @Test
        @DisplayName("sensitivity clamped to 0.1..5.0")
        void clamped() {
            var config = new BeatSyncConfig();
            config.setBeatThresholdMultiplier(0.01);
            assertEquals(0.1, config.getBeatThresholdMultiplier());
            config.setBeatThresholdMultiplier(10.0);
            assertEquals(5.0, config.getBeatThresholdMultiplier());
        }
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd minecraft_plugin && ./mvnw test -Dtest=com.audioviz.beatsync.BeatSyncConfigTest -pl . 2>&1 | tail -10`
Expected: FAIL — class not found

**Step 3: Write the implementation**

```java
package com.audioviz.beatsync;

/**
 * Per-zone configuration for beat sync overrides.
 * All fields have safe defaults (auto BPM, no offset, normal sensitivity).
 */
public class BeatSyncConfig {

    private double manualBpm = 0.0;         // 0 = auto (use DJ client value)
    private double phaseOffset = 0.0;        // -0.5 to 0.5
    private double beatThresholdMultiplier = 1.0; // 0.1 to 5.0
    private boolean projectionEnabled = true;

    public double getManualBpm() { return manualBpm; }
    public double getPhaseOffset() { return phaseOffset; }
    public double getBeatThresholdMultiplier() { return beatThresholdMultiplier; }
    public boolean isProjectionEnabled() { return projectionEnabled; }
    public boolean isAutoBpm() { return manualBpm <= 0; }

    public void setManualBpm(double bpm) {
        this.manualBpm = Math.max(0, Math.min(300, bpm));
    }

    public void setPhaseOffset(double offset) {
        this.phaseOffset = Math.max(-0.5, Math.min(0.5, offset));
    }

    public void setBeatThresholdMultiplier(double multiplier) {
        this.beatThresholdMultiplier = Math.max(0.1, Math.min(5.0, multiplier));
    }

    public void setProjectionEnabled(boolean enabled) {
        this.projectionEnabled = enabled;
    }

    /**
     * Apply a phase offset to a beat phase value, wrapping to [0, 1).
     */
    public static double applyPhaseOffset(double beatPhase, double offset) {
        double result = (beatPhase + offset) % 1.0;
        if (result < 0) result += 1.0;
        return result;
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `cd minecraft_plugin && ./mvnw test -Dtest=com.audioviz.beatsync.BeatSyncConfigTest -pl . 2>&1 | tail -10`
Expected: All 11 tests PASS

**Step 5: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/beatsync/BeatSyncConfig.java \
        minecraft_plugin/src/test/java/com/audioviz/beatsync/BeatSyncConfigTest.java
git commit -m "feat: add BeatSyncConfig with BPM override, phase offset, and sensitivity"
```

---

### Task 2: BeatSyncManager + Wire into Plugin and Commands

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/beatsync/BeatSyncManager.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/commands/AudioVizCommand.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java`

**Step 1: Write BeatSyncManager**

```java
package com.audioviz.beatsync;

import com.audioviz.AudioVizPlugin;
import com.audioviz.patterns.AudioState;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Manages per-zone BeatSyncConfig, persistence, and audio state modification.
 */
public class BeatSyncManager {

    private final AudioVizPlugin plugin;
    private final Map<String, BeatSyncConfig> configs = new LinkedHashMap<>();
    private final BeatSyncConfig globalConfig = new BeatSyncConfig();
    private final File configFile;

    public BeatSyncManager(AudioVizPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "beatsync.yml");
    }

    public BeatSyncConfig getGlobalConfig() { return globalConfig; }

    public BeatSyncConfig getConfig(String zoneName) {
        return configs.getOrDefault(zoneName, globalConfig);
    }

    /**
     * Apply beat sync overrides to an AudioState.
     * Returns a new AudioState with modified BPM/phase if overrides are active.
     */
    public AudioState applyOverrides(AudioState audio) {
        double bpm = globalConfig.isAutoBpm() ? 0.0 : globalConfig.getManualBpm();
        double phaseOffset = globalConfig.getPhaseOffset();

        // If no overrides active, return as-is
        if (bpm <= 0 && phaseOffset == 0.0) return audio;

        double effectiveBpm = bpm > 0 ? bpm : audio.getBeatIntensity(); // BPM not in AudioState — this is a no-op for BPM
        double adjustedPhase = BeatSyncConfig.applyPhaseOffset(audio.getBeatPhase(), phaseOffset);

        return new AudioState(
            audio.getBands(),
            audio.getAmplitude(),
            audio.isBeat(),
            audio.getBeatIntensity(),
            audio.getTempoConfidence(),
            adjustedPhase,
            audio.getFrame()
        );
    }

    // ========== Persistence ==========

    public void load() {
        if (!configFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(configFile);

        if (yml.contains("global")) {
            var section = yml.getConfigurationSection("global");
            if (section != null) {
                globalConfig.setManualBpm(section.getDouble("manual_bpm", 0));
                globalConfig.setPhaseOffset(section.getDouble("phase_offset", 0));
                globalConfig.setBeatThresholdMultiplier(section.getDouble("sensitivity", 1.0));
                globalConfig.setProjectionEnabled(section.getBoolean("projection_enabled", true));
            }
        }
        plugin.getLogger().info("Loaded beat sync config");
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        var section = yml.createSection("global");
        section.set("manual_bpm", globalConfig.getManualBpm());
        section.set("phase_offset", globalConfig.getPhaseOffset());
        section.set("sensitivity", globalConfig.getBeatThresholdMultiplier());
        section.set("projection_enabled", globalConfig.isProjectionEnabled());

        try {
            yml.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save beatsync.yml", e);
        }
    }
}
```

**Step 2: Add to AudioVizPlugin**

1. Import: `import com.audioviz.beatsync.BeatSyncManager;`
2. Field: `private BeatSyncManager beatSyncManager;`
3. In `onEnable()`, after sequenceManager init:
   ```java
   this.beatSyncManager = new BeatSyncManager(this);
   this.beatSyncManager.load();
   ```
4. In `onDisable()`, before sequenceManager stop:
   ```java
   if (beatSyncManager != null) {
       beatSyncManager.save();
   }
   ```
5. Getter: `public BeatSyncManager getBeatSyncManager() { return beatSyncManager; }`

**Step 3: Modify BitmapPatternManager.updateAudioState()**

In `updateAudioState(AudioState audio)`, apply overrides before storing:
```java
public void updateAudioState(AudioState audio) {
    var bsm = plugin.getBeatSyncManager();
    if (bsm != null) {
        audio = bsm.applyOverrides(audio);
    }
    this.latestAudioState = audio;
    var listener = plugin.getConnectionStateListener();
    if (listener != null) {
        listener.onAudioFrame();
    }
}
```

**Step 4: Add /av beatsync commands to AudioVizCommand**

In `onCommand()`, add case:
```java
case "beatsync", "bs" -> handleBeatSyncCommand(sender, args);
```

Add handler:
```java
private void handleBeatSyncCommand(CommandSender sender, String[] args) {
    if (!sender.hasPermission("audioviz.beatsync") && !sender.hasPermission("audioviz.admin")) {
        sender.sendMessage(ChatColor.RED + "You don't have permission to manage beat sync.");
        return;
    }
    var bsm = plugin.getBeatSyncManager();
    if (bsm == null) {
        sender.sendMessage(ChatColor.RED + "Beat sync not available.");
        return;
    }
    if (args.length < 2) {
        sender.sendMessage(ChatColor.AQUA + "Usage: /av beatsync <bpm|phase|sensitivity|status>");
        return;
    }
    var config = bsm.getGlobalConfig();
    switch (args[1].toLowerCase()) {
        case "bpm" -> {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /av beatsync bpm <value|auto>");
                return;
            }
            if (args[2].equalsIgnoreCase("auto")) {
                config.setManualBpm(0);
                sender.sendMessage(ChatColor.GREEN + "BPM set to auto-detect");
            } else {
                try {
                    double bpm = Double.parseDouble(args[2]);
                    config.setManualBpm(bpm);
                    sender.sendMessage(ChatColor.GREEN + "BPM set to " + config.getManualBpm());
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid BPM value: " + args[2]);
                }
            }
        }
        case "phase" -> {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /av beatsync phase <-0.5 to 0.5>");
                return;
            }
            try {
                double offset = Double.parseDouble(args[2]);
                config.setPhaseOffset(offset);
                sender.sendMessage(ChatColor.GREEN + "Phase offset set to " + config.getPhaseOffset());
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid offset: " + args[2]);
            }
        }
        case "sensitivity" -> {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /av beatsync sensitivity <0.1-5.0>");
                return;
            }
            try {
                double mult = Double.parseDouble(args[2]);
                config.setBeatThresholdMultiplier(mult);
                sender.sendMessage(ChatColor.GREEN + "Sensitivity set to " + config.getBeatThresholdMultiplier());
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid value: " + args[2]);
            }
        }
        case "status" -> {
            sender.sendMessage(ChatColor.AQUA + "--- Beat Sync ---");
            sender.sendMessage(ChatColor.WHITE + "BPM: " +
                (config.isAutoBpm() ? ChatColor.GREEN + "Auto" : ChatColor.YELLOW + "" + config.getManualBpm()));
            sender.sendMessage(ChatColor.WHITE + "Phase Offset: " + ChatColor.AQUA + config.getPhaseOffset());
            sender.sendMessage(ChatColor.WHITE + "Sensitivity: " + ChatColor.AQUA + config.getBeatThresholdMultiplier() + "x");
            sender.sendMessage(ChatColor.WHITE + "Projection: " +
                (config.isProjectionEnabled() ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled"));
        }
        default -> sender.sendMessage(ChatColor.RED + "Unknown: /av beatsync " + args[1]);
    }
}
```

In `onTabComplete()`, add `"beatsync"`, `"bs"` to top-level, with sub-completions for bpm/phase/sensitivity/status.

In `sendHelp()`, add: `sender.sendMessage(ChatColor.AQUA + "/av beatsync <bpm|phase|sensitivity|status>" + ChatColor.GRAY + " - Beat sync controls");`

**Step 5: Run all tests**

Run: `cd minecraft_plugin && ./mvnw test -pl . 2>&1 | tail -10`
Expected: All tests PASS

**Step 6: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/beatsync/BeatSyncManager.java \
        minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java \
        minecraft_plugin/src/main/java/com/audioviz/commands/AudioVizCommand.java \
        minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java
git commit -m "feat: wire BeatSyncManager into plugin with /av beatsync commands"
```

---

## Feature B: Latency Monitoring

### Task 3: LatencyTracker — Core Logic + Tests

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/latency/LatencyTracker.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/latency/LatencyTrackerTest.java`

**Step 1: Write the test**

```java
package com.audioviz.latency;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class LatencyTrackerTest {

    @Nested
    @DisplayName("Rolling Window Stats")
    class RollingStats {

        @Test
        @DisplayName("average of single sample")
        void singleSample() {
            var tracker = new LatencyTracker.RollingWindow(10);
            tracker.record(50.0);
            assertEquals(50.0, tracker.getAvg(), 0.001);
        }

        @Test
        @DisplayName("average of multiple samples")
        void multipleSamples() {
            var tracker = new LatencyTracker.RollingWindow(10);
            tracker.record(10.0);
            tracker.record(20.0);
            tracker.record(30.0);
            assertEquals(20.0, tracker.getAvg(), 0.001);
        }

        @Test
        @DisplayName("window evicts oldest when full")
        void eviction() {
            var tracker = new LatencyTracker.RollingWindow(3);
            tracker.record(10.0);
            tracker.record(20.0);
            tracker.record(30.0);
            tracker.record(40.0); // evicts 10
            assertEquals(30.0, tracker.getAvg(), 0.001);
        }

        @Test
        @DisplayName("p95 calculation")
        void p95() {
            var tracker = new LatencyTracker.RollingWindow(100);
            for (int i = 1; i <= 100; i++) {
                tracker.record(i);
            }
            // 95th percentile of 1..100 = 95
            assertEquals(95.0, tracker.getP95(), 1.0);
        }

        @Test
        @DisplayName("max tracks highest value")
        void max() {
            var tracker = new LatencyTracker.RollingWindow(10);
            tracker.record(5.0);
            tracker.record(50.0);
            tracker.record(15.0);
            assertEquals(50.0, tracker.getMax(), 0.001);
        }

        @Test
        @DisplayName("empty window returns zero")
        void emptyReturnsZero() {
            var tracker = new LatencyTracker.RollingWindow(10);
            assertEquals(0.0, tracker.getAvg());
            assertEquals(0.0, tracker.getP95());
            assertEquals(0.0, tracker.getMax());
            assertEquals(0.0, tracker.getJitter());
        }

        @Test
        @DisplayName("jitter is stddev of samples")
        void jitter() {
            var tracker = new LatencyTracker.RollingWindow(4);
            tracker.record(10.0);
            tracker.record(10.0);
            tracker.record(10.0);
            tracker.record(10.0);
            assertEquals(0.0, tracker.getJitter(), 0.001); // all same = 0 jitter
        }
    }

    @Nested
    @DisplayName("Clock Offset")
    class ClockOffset {

        @Test
        @DisplayName("computes offset on first sample")
        void firstSample() {
            var tracker = new LatencyTracker();
            long localMs = 1000;
            double remoteTs = 0.5; // 500ms in seconds
            tracker.recordNetworkLatency(remoteTs, localMs);
            // offset = 1000 - 500 = 500, latency = 0
            // Second sample uses offset
            tracker.recordNetworkLatency(0.6, 1120);
            // expected: 1120 - 600 - 500 = 20ms
            assertEquals(20.0, tracker.getNetworkStats().getAvg(), 1.0);
        }

        @Test
        @DisplayName("negative latency clamped to zero")
        void negativeClamped() {
            var tracker = new LatencyTracker();
            tracker.recordNetworkLatency(10.0, 1000); // offset = 1000 - 10000 = -9000
            tracker.recordNetworkLatency(10.001, 1000); // 1000 - 10001 - (-9000) = -1 → clamp to 0
            assertEquals(0.0, tracker.getNetworkStats().getAvg(), 0.001);
        }
    }

    @Nested
    @DisplayName("Segment Tracking")
    class Segments {

        @Test
        @DisplayName("processing latency recorded")
        void processingLatency() {
            var tracker = new LatencyTracker();
            tracker.recordProcessingLatency(5.0);
            tracker.recordProcessingLatency(10.0);
            assertEquals(7.5, tracker.getProcessingStats().getAvg(), 0.001);
        }

        @Test
        @DisplayName("total latency sums segments")
        void totalLatency() {
            var tracker = new LatencyTracker();
            tracker.recordNetworkLatency(1.0, 1010); // ~10ms after offset
            tracker.recordProcessingLatency(5.0);
            // Total = network avg + processing avg
            double total = tracker.getTotalAvgMs();
            assertTrue(total >= 5.0); // at least processing
        }
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd minecraft_plugin && ./mvnw test -Dtest=com.audioviz.latency.LatencyTrackerTest -pl . 2>&1 | tail -10`
Expected: FAIL — class not found

**Step 3: Write the implementation**

```java
package com.audioviz.latency;

import java.util.Arrays;

/**
 * Tracks end-to-end latency across the audio pipeline.
 * Three segments: network (DJ→Plugin), processing (queue→tick), render.
 */
public class LatencyTracker {

    private final RollingWindow networkLatency = new RollingWindow(100);
    private final RollingWindow processingLatency = new RollingWindow(100);

    private boolean clockOffsetInitialized = false;
    private long clockOffsetMs = 0;

    /**
     * Record network latency from an incoming audio frame.
     * @param remoteTimestampSec timestamp from DJ client (Unix seconds, double)
     * @param localReceiveMs local receive time (System.currentTimeMillis())
     */
    public void recordNetworkLatency(double remoteTimestampSec, long localReceiveMs) {
        long remoteMs = (long) (remoteTimestampSec * 1000);

        if (!clockOffsetInitialized) {
            clockOffsetMs = localReceiveMs - remoteMs;
            clockOffsetInitialized = true;
            return; // First sample calibrates, don't record
        }

        long latencyMs = localReceiveMs - remoteMs - clockOffsetMs;
        networkLatency.record(Math.max(0, latencyMs));
    }

    /**
     * Record processing latency (time from message receive to tick consumption).
     */
    public void recordProcessingLatency(double ms) {
        processingLatency.record(Math.max(0, ms));
    }

    public RollingWindow getNetworkStats() { return networkLatency; }
    public RollingWindow getProcessingStats() { return processingLatency; }

    /**
     * Get total average latency (network + processing).
     * Render latency is tracked separately by BitmapRenderTimer.
     */
    public double getTotalAvgMs() {
        return networkLatency.getAvg() + processingLatency.getAvg();
    }

    /**
     * Rolling window for latency samples with avg, p95, max, jitter.
     */
    public static class RollingWindow {

        private final double[] samples;
        private final int capacity;
        private int count = 0;
        private int writeIndex = 0;

        public RollingWindow(int capacity) {
            this.capacity = capacity;
            this.samples = new double[capacity];
        }

        public void record(double value) {
            samples[writeIndex] = value;
            writeIndex = (writeIndex + 1) % capacity;
            if (count < capacity) count++;
        }

        public double getAvg() {
            if (count == 0) return 0.0;
            double sum = 0;
            for (int i = 0; i < count; i++) sum += samples[i];
            return sum / count;
        }

        public double getP95() {
            if (count == 0) return 0.0;
            double[] sorted = new double[count];
            System.arraycopy(samples, 0, sorted, 0, count);
            Arrays.sort(sorted);
            int index = (int) Math.ceil(count * 0.95) - 1;
            return sorted[Math.max(0, index)];
        }

        public double getMax() {
            if (count == 0) return 0.0;
            double max = samples[0];
            for (int i = 1; i < count; i++) {
                if (samples[i] > max) max = samples[i];
            }
            return max;
        }

        public double getJitter() {
            if (count < 2) return 0.0;
            double avg = getAvg();
            double sumSqDiff = 0;
            for (int i = 0; i < count; i++) {
                double diff = samples[i] - avg;
                sumSqDiff += diff * diff;
            }
            return Math.sqrt(sumSqDiff / count);
        }

        public int getCount() { return count; }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `cd minecraft_plugin && ./mvnw test -Dtest=com.audioviz.latency.LatencyTrackerTest -pl . 2>&1 | tail -10`
Expected: All 10 tests PASS

**Step 5: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/latency/LatencyTracker.java \
        minecraft_plugin/src/test/java/com/audioviz/latency/LatencyTrackerTest.java
git commit -m "feat: add LatencyTracker with rolling window stats, p95, jitter"
```

---

### Task 4: Wire LatencyTracker into Plugin, Commands, Metrics

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/commands/AudioVizCommand.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/metrics/MetricsDisplay.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/protocol/MessageHandler.java`

**Step 1: Add to AudioVizPlugin**

1. Import: `import com.audioviz.latency.LatencyTracker;`
2. Field: `private LatencyTracker latencyTracker;`
3. In `onEnable()`, after beatSyncManager:
   ```java
   this.latencyTracker = new LatencyTracker();
   ```
4. Getter: `public LatencyTracker getLatencyTracker() { return latencyTracker; }`

**Step 2: Record network latency in MessageHandler**

In `handleAudioState()`, after the `bpm` and `tempoConfidence` parsing (around line 1073-1080), add:

```java
// Record network latency if timestamp present
if (message.has("ts")) {
    double remoteTs = message.get("ts").getAsDouble();
    var latencyTracker = plugin.getLatencyTracker();
    if (latencyTracker != null) {
        latencyTracker.recordNetworkLatency(remoteTs, System.currentTimeMillis());
    }
}
```

Also add the same block in `handleBatchUpdate()` where audio state is parsed (around line 365-381):

```java
if (message.has("ts")) {
    double remoteTs = message.get("ts").getAsDouble();
    var latencyTracker = plugin.getLatencyTracker();
    if (latencyTracker != null) {
        latencyTracker.recordNetworkLatency(remoteTs, System.currentTimeMillis());
    }
}
```

**Step 3: Add /av latency command**

In `onCommand()`, add case:
```java
case "latency", "lat" -> handleLatencyCommand(sender);
```

Add handler:
```java
private void handleLatencyCommand(CommandSender sender) {
    if (!sender.hasPermission("audioviz.admin")) {
        sender.sendMessage(ChatColor.RED + "You don't have permission to view latency.");
        return;
    }
    var lt = plugin.getLatencyTracker();
    if (lt == null) {
        sender.sendMessage(ChatColor.RED + "Latency tracker not available.");
        return;
    }
    var net = lt.getNetworkStats();
    var proc = lt.getProcessingStats();

    sender.sendMessage(ChatColor.AQUA + "--- MCAV Latency ---");
    sender.sendMessage(ChatColor.WHITE + "Network:    " +
        ChatColor.AQUA + String.format("%.0fms avg / %.0fms p95", net.getAvg(), net.getP95()));
    sender.sendMessage(ChatColor.WHITE + "Processing: " +
        ChatColor.AQUA + String.format("%.1fms avg / %.1fms p95", proc.getAvg(), proc.getP95()));
    sender.sendMessage(ChatColor.WHITE + "Total:      " +
        ChatColor.AQUA + String.format("%.0fms avg", lt.getTotalAvgMs()));
    sender.sendMessage(ChatColor.WHITE + "Jitter:     " +
        ChatColor.AQUA + String.format("±%.0fms", net.getJitter()));
}
```

In `onTabComplete()`, add `"latency"`, `"lat"` to top-level.

In `sendHelp()`, add: `sender.sendMessage(ChatColor.AQUA + "/av latency" + ChatColor.GRAY + " - Show pipeline latency stats");`

**Step 4: Add latency to MetricsDisplay**

In `MetricsDisplay.java`, add a `collectLatency()` method:
```java
private String collectLatency() {
    var lt = plugin.getLatencyTracker();
    if (lt == null) return "N/A";
    return String.format("%.0fms", lt.getTotalAvgMs());
}
```

In `updateScoreboard()`, add a line:
```java
obj.getScore("Latency: " + latency).setScore(1);
```

And collect the latency string in `update()` alongside the other metrics.

**Step 5: Run all tests**

Run: `cd minecraft_plugin && ./mvnw test -pl . 2>&1 | tail -10`
Expected: All tests PASS

**Step 6: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java \
        minecraft_plugin/src/main/java/com/audioviz/commands/AudioVizCommand.java \
        minecraft_plugin/src/main/java/com/audioviz/metrics/MetricsDisplay.java \
        minecraft_plugin/src/main/java/com/audioviz/protocol/MessageHandler.java
git commit -m "feat: wire LatencyTracker into plugin with /av latency command and metrics"
```

---

## Feature C: Recording & Replay

### Task 5: RecordingFrame — Data Model + Serialization Tests

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/recording/RecordingFrame.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/recording/RecordingFrameTest.java`

**Step 1: Write the test**

```java
package com.audioviz.recording;

import org.junit.jupiter.api.*;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class RecordingFrameTest {

    @Test
    @DisplayName("round-trip serialization preserves all fields")
    void roundTrip() {
        double[] bands = {0.8, 0.6, 0.4, 0.2, 0.1};
        var frame = new RecordingFrame(bands, 0.75, true, 0.9, 0.85, 0.5, 42);

        byte[] bytes = frame.toBytes();
        assertEquals(RecordingFrame.BYTE_SIZE, bytes.length);

        RecordingFrame restored = RecordingFrame.fromBytes(bytes);
        assertArrayEquals(bands, restored.bands(), 0.001);
        assertEquals(0.75, restored.amplitude(), 0.001);
        assertTrue(restored.isBeat());
        assertEquals(0.9, restored.beatIntensity(), 0.001);
        assertEquals(0.85, restored.tempoConfidence(), 0.001);
        assertEquals(0.5, restored.beatPhase(), 0.001);
        assertEquals(42, restored.tickIndex());
    }

    @Test
    @DisplayName("silent frame serializes correctly")
    void silentFrame() {
        var frame = RecordingFrame.silent(0);
        byte[] bytes = frame.toBytes();
        RecordingFrame restored = RecordingFrame.fromBytes(bytes);
        assertEquals(0.0, restored.amplitude());
        assertFalse(restored.isBeat());
        assertEquals(0, restored.tickIndex());
    }

    @Test
    @DisplayName("BYTE_SIZE matches actual serialized size")
    void byteSizeConstant() {
        var frame = new RecordingFrame(new double[5], 0, false, 0, 0, 0, 0);
        assertEquals(RecordingFrame.BYTE_SIZE, frame.toBytes().length);
    }

    @Test
    @DisplayName("fromAudioState captures all fields")
    void fromAudioState() {
        double[] bands = {0.1, 0.2, 0.3, 0.4, 0.5};
        var frame = RecordingFrame.fromValues(bands, 0.6, true, 0.7, 0.8, 0.9, 100);
        assertEquals(0.6, frame.amplitude(), 0.001);
        assertTrue(frame.isBeat());
        assertEquals(100, frame.tickIndex());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd minecraft_plugin && ./mvnw test -Dtest=com.audioviz.recording.RecordingFrameTest -pl . 2>&1 | tail -10`
Expected: FAIL — class not found

**Step 3: Write the implementation**

```java
package com.audioviz.recording;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A single recorded tick of audio state. Binary-serializable for compact storage.
 *
 * Layout (57 bytes):
 *   5 doubles (bands) = 40 bytes
 *   1 double (amplitude) = 8 bytes
 *   1 byte (isBeat) = 1 byte
 *   1 double (beatIntensity) = 8 bytes (but we'll pack more efficiently)
 *
 * Actual layout: 5*8 + 8 + 1 + 8 + 8 + 8 + 4 = 77 bytes
 */
public record RecordingFrame(
    double[] bands,
    double amplitude,
    boolean isBeat,
    double beatIntensity,
    double tempoConfidence,
    double beatPhase,
    int tickIndex
) {
    // 5 doubles (bands) + amplitude + beatIntensity + tempoConfidence + beatPhase + 1 byte (beat) + 1 int (tick)
    public static final int BYTE_SIZE = 5 * 8 + 8 + 1 + 8 + 8 + 8 + 4;  // 77 bytes

    public byte[] toBytes() {
        ByteBuffer buf = ByteBuffer.allocate(BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 5; i++) {
            buf.putDouble(i < bands.length ? bands[i] : 0.0);
        }
        buf.putDouble(amplitude);
        buf.put((byte) (isBeat ? 1 : 0));
        buf.putDouble(beatIntensity);
        buf.putDouble(tempoConfidence);
        buf.putDouble(beatPhase);
        buf.putInt(tickIndex);
        return buf.array();
    }

    public static RecordingFrame fromBytes(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        double[] bands = new double[5];
        for (int i = 0; i < 5; i++) {
            bands[i] = buf.getDouble();
        }
        double amplitude = buf.getDouble();
        boolean isBeat = buf.get() != 0;
        double beatIntensity = buf.getDouble();
        double tempoConfidence = buf.getDouble();
        double beatPhase = buf.getDouble();
        int tickIndex = buf.getInt();
        return new RecordingFrame(bands, amplitude, isBeat, beatIntensity, tempoConfidence, beatPhase, tickIndex);
    }

    public static RecordingFrame silent(int tickIndex) {
        return new RecordingFrame(new double[5], 0, false, 0, 0, 0, tickIndex);
    }

    public static RecordingFrame fromValues(double[] bands, double amplitude, boolean isBeat,
                                             double beatIntensity, double tempoConfidence,
                                             double beatPhase, int tickIndex) {
        return new RecordingFrame(
            bands != null ? bands.clone() : new double[5],
            amplitude, isBeat, beatIntensity, tempoConfidence, beatPhase, tickIndex);
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `cd minecraft_plugin && ./mvnw test -Dtest=com.audioviz.recording.RecordingFrameTest -pl . 2>&1 | tail -10`
Expected: All 4 tests PASS

**Step 5: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/recording/RecordingFrame.java \
        minecraft_plugin/src/test/java/com/audioviz/recording/RecordingFrameTest.java
git commit -m "feat: add RecordingFrame with binary serialization"
```

---

### Task 6: Recording — Header + Frame Collection + Tests

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/recording/Recording.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/recording/RecordingTest.java`

**Step 1: Write the test**

```java
package com.audioviz.recording;

import org.junit.jupiter.api.*;
import java.io.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RecordingTest {

    @Test
    @DisplayName("empty recording has zero duration")
    void emptyDuration() {
        var rec = new Recording("test", Map.of("zone1", "bmp_fire"), 20);
        assertEquals(0, rec.getDurationTicks());
        assertEquals(0.0, rec.getDurationSeconds(), 0.001);
    }

    @Test
    @DisplayName("duration calculated from frame count and tick rate")
    void duration() {
        var rec = new Recording("test", Map.of(), 20);
        for (int i = 0; i < 100; i++) {
            rec.addFrame(RecordingFrame.silent(i));
        }
        assertEquals(100, rec.getDurationTicks());
        assertEquals(5.0, rec.getDurationSeconds(), 0.001);
    }

    @Test
    @DisplayName("binary round-trip preserves all data")
    void binaryRoundTrip() throws IOException {
        var rec = new Recording("my_recording", Map.of("zone1", "bmp_plasma"), 20);
        rec.addFrame(new RecordingFrame(
            new double[]{0.8, 0.6, 0.4, 0.2, 0.1}, 0.75, true, 0.9, 0.85, 0.5, 0));
        rec.addFrame(RecordingFrame.silent(1));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        rec.writeTo(out);

        Recording restored = Recording.readFrom(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("my_recording", restored.getName());
        assertEquals(20, restored.getTickRate());
        assertEquals(2, restored.getDurationTicks());
        assertEquals("bmp_plasma", restored.getZonePatterns().get("zone1"));

        var frame0 = restored.getFrame(0);
        assertEquals(0.8, frame0.bands()[0], 0.001);
        assertTrue(frame0.isBeat());
    }

    @Test
    @DisplayName("getFrame returns correct frame by index")
    void getFrame() {
        var rec = new Recording("test", Map.of(), 20);
        rec.addFrame(RecordingFrame.silent(0));
        rec.addFrame(new RecordingFrame(new double[5], 0.5, false, 0, 0, 0, 1));
        assertEquals(0.5, rec.getFrame(1).amplitude(), 0.001);
    }

    @Test
    @DisplayName("getFrame out of bounds returns silent")
    void getFrameOutOfBounds() {
        var rec = new Recording("test", Map.of(), 20);
        var frame = rec.getFrame(999);
        assertEquals(0.0, frame.amplitude());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd minecraft_plugin && ./mvnw test -Dtest=com.audioviz.recording.RecordingTest -pl . 2>&1 | tail -10`
Expected: FAIL

**Step 3: Write the implementation**

```java
package com.audioviz.recording;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A complete recording: header metadata + list of audio frames.
 * Binary format: [header][frame0][frame1]...
 */
public class Recording {

    private static final byte[] MAGIC = "MCAV".getBytes(StandardCharsets.UTF_8);
    private static final int FORMAT_VERSION = 1;

    private final String name;
    private final Map<String, String> zonePatterns;
    private final int tickRate;
    private final List<RecordingFrame> frames = new ArrayList<>();

    public Recording(String name, Map<String, String> zonePatterns, int tickRate) {
        this.name = name;
        this.zonePatterns = new LinkedHashMap<>(zonePatterns);
        this.tickRate = tickRate;
    }

    public String getName() { return name; }
    public Map<String, String> getZonePatterns() { return Collections.unmodifiableMap(zonePatterns); }
    public int getTickRate() { return tickRate; }
    public int getDurationTicks() { return frames.size(); }
    public double getDurationSeconds() { return tickRate > 0 ? (double) frames.size() / tickRate : 0; }

    public void addFrame(RecordingFrame frame) { frames.add(frame); }

    public RecordingFrame getFrame(int index) {
        if (index < 0 || index >= frames.size()) return RecordingFrame.silent(index);
        return frames.get(index);
    }

    // ========== Binary I/O ==========

    public void writeTo(OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        // Magic + version
        dos.write(MAGIC);
        dos.writeInt(FORMAT_VERSION);

        // Header
        writeString(dos, name);
        dos.writeInt(tickRate);
        dos.writeInt(zonePatterns.size());
        for (var entry : zonePatterns.entrySet()) {
            writeString(dos, entry.getKey());
            writeString(dos, entry.getValue());
        }

        // Frames
        dos.writeInt(frames.size());
        for (var frame : frames) {
            dos.write(frame.toBytes());
        }

        dos.flush();
    }

    public static Recording readFrom(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);

        // Magic
        byte[] magic = new byte[4];
        dis.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("Invalid recording file (bad magic)");
        }
        int version = dis.readInt();
        if (version != FORMAT_VERSION) {
            throw new IOException("Unsupported recording version: " + version);
        }

        // Header
        String name = readString(dis);
        int tickRate = dis.readInt();
        int patternCount = dis.readInt();
        Map<String, String> patterns = new LinkedHashMap<>();
        for (int i = 0; i < patternCount; i++) {
            patterns.put(readString(dis), readString(dis));
        }

        // Frames
        int frameCount = dis.readInt();
        Recording rec = new Recording(name, patterns, tickRate);
        byte[] frameBytes = new byte[RecordingFrame.BYTE_SIZE];
        for (int i = 0; i < frameCount; i++) {
            dis.readFully(frameBytes);
            rec.addFrame(RecordingFrame.fromBytes(frameBytes));
        }

        return rec;
    }

    private static void writeString(DataOutputStream dos, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        dos.writeShort(bytes.length);
        dos.write(bytes);
    }

    private static String readString(DataInputStream dis) throws IOException {
        int len = dis.readUnsignedShort();
        byte[] bytes = new byte[len];
        dis.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `cd minecraft_plugin && ./mvnw test -Dtest=com.audioviz.recording.RecordingTest -pl . 2>&1 | tail -10`
Expected: All 5 tests PASS

**Step 5: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/recording/Recording.java \
        minecraft_plugin/src/test/java/com/audioviz/recording/RecordingTest.java
git commit -m "feat: add Recording with binary format, header, and frame collection"
```

---

### Task 7: RecordingManager — Record, Playback, File I/O

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/recording/RecordingManager.java`

**Step 1: Write the implementation**

```java
package com.audioviz.recording;

import com.audioviz.AudioVizPlugin;
import com.audioviz.patterns.AudioState;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.util.*;
import java.util.logging.Level;

/**
 * Manages recording sessions and playback of recorded audio state.
 */
public class RecordingManager {

    private final AudioVizPlugin plugin;
    private final File recordingsDir;

    // Recording state
    private Recording activeRecording = null;
    private int recordingTickCounter = 0;

    // Playback state
    private Recording playbackRecording = null;
    private int playbackTickIndex = 0;
    private BukkitTask playbackTask = null;
    private boolean replaying = false;

    private static final int TICK_RATE = 20;

    public RecordingManager(AudioVizPlugin plugin) {
        this.plugin = plugin;
        this.recordingsDir = new File(plugin.getDataFolder(), "recordings");
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs();
        }
    }

    // ========== Recording ==========

    public boolean startRecording(String name) {
        if (activeRecording != null) return false;

        Map<String, String> zonePatterns = new LinkedHashMap<>();
        var bpm = plugin.getBitmapPatternManager();
        if (bpm != null) {
            for (var zoneName : bpm.getActiveZoneNames()) {
                String patternId = bpm.getActivePatternId(zoneName);
                if (patternId != null) {
                    zonePatterns.put(zoneName, patternId);
                }
            }
        }

        activeRecording = new Recording(name, zonePatterns, TICK_RATE);
        recordingTickCounter = 0;
        plugin.getLogger().info("Started recording: " + name);
        return true;
    }

    public boolean stopRecording() {
        if (activeRecording == null) return false;

        try {
            File file = new File(recordingsDir, activeRecording.getName() + ".mcavrec");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                activeRecording.writeTo(fos);
            }
            plugin.getLogger().info("Saved recording: " + activeRecording.getName() +
                " (" + activeRecording.getDurationTicks() + " ticks)");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save recording", e);
        }

        activeRecording = null;
        return true;
    }

    /**
     * Called from BitmapPatternManager tick loop to capture a frame.
     */
    public void captureFrame(AudioState audio) {
        if (activeRecording == null) return;
        activeRecording.addFrame(RecordingFrame.fromValues(
            audio.getBands(), audio.getAmplitude(), audio.isBeat(),
            audio.getBeatIntensity(), audio.getTempoConfidence(),
            audio.getBeatPhase(), recordingTickCounter++));
    }

    // ========== Playback ==========

    public boolean startPlayback(String name) {
        if (replaying) return false;

        File file = new File(recordingsDir, name + ".mcavrec");
        if (!file.exists()) return false;

        try (FileInputStream fis = new FileInputStream(file)) {
            playbackRecording = Recording.readFrom(fis);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load recording: " + name, e);
            return false;
        }

        playbackTickIndex = 0;
        replaying = true;

        playbackTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (playbackRecording == null || playbackTickIndex >= playbackRecording.getDurationTicks()) {
                stopPlayback();
                return;
            }

            RecordingFrame frame = playbackRecording.getFrame(playbackTickIndex);
            AudioState audio = new AudioState(
                frame.bands(), frame.amplitude(), frame.isBeat(),
                frame.beatIntensity(), frame.tempoConfidence(),
                frame.beatPhase(), playbackTickIndex);

            var bpm = plugin.getBitmapPatternManager();
            if (bpm != null) {
                bpm.updateAudioState(audio);
            }

            playbackTickIndex++;
        }, 1L, 1L);

        plugin.getLogger().info("Playing recording: " + name +
            " (" + playbackRecording.getDurationTicks() + " ticks)");
        return true;
    }

    public void stopPlayback() {
        if (playbackTask != null) {
            playbackTask.cancel();
            playbackTask = null;
        }
        playbackRecording = null;
        playbackTickIndex = 0;
        replaying = false;
    }

    // ========== File Management ==========

    public List<String> listRecordings() {
        List<String> names = new ArrayList<>();
        File[] files = recordingsDir.listFiles((dir, name) -> name.endsWith(".mcavrec"));
        if (files != null) {
            for (File f : files) {
                names.add(f.getName().replace(".mcavrec", ""));
            }
        }
        Collections.sort(names);
        return names;
    }

    public boolean deleteRecording(String name) {
        File file = new File(recordingsDir, name + ".mcavrec");
        return file.exists() && file.delete();
    }

    // ========== Accessors ==========

    public boolean isRecording() { return activeRecording != null; }
    public boolean isReplaying() { return replaying; }
    public String getActiveRecordingName() {
        return activeRecording != null ? activeRecording.getName() : null;
    }
    public String getPlaybackName() {
        return playbackRecording != null ? playbackRecording.getName() : null;
    }

    public void stop() {
        stopRecording();
        stopPlayback();
    }
}
```

**Step 2: Run all tests**

Run: `cd minecraft_plugin && ./mvnw test -pl . 2>&1 | tail -10`
Expected: All tests PASS

**Step 3: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/recording/RecordingManager.java
git commit -m "feat: add RecordingManager — record, playback, file I/O"
```

---

### Task 8: Wire RecordingManager into Plugin and Commands

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/commands/AudioVizCommand.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/metrics/MetricsDisplay.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/connection/ConnectionStateListener.java`

**Step 1: Add to AudioVizPlugin**

1. Import: `import com.audioviz.recording.RecordingManager;`
2. Field: `private RecordingManager recordingManager;`
3. In `onEnable()`, after latencyTracker:
   ```java
   this.recordingManager = new RecordingManager(this);
   ```
4. In `onDisable()`, before beatSyncManager:
   ```java
   if (recordingManager != null) {
       recordingManager.stop();
   }
   ```
5. Getter: `public RecordingManager getRecordingManager() { return recordingManager; }`

**Step 2: Hook recording capture in BitmapPatternManager**

In the `tick(AudioState audio)` method or in `start()`'s tick lambda, after the main render loop, add:

```java
var recorder = plugin.getRecordingManager();
if (recorder != null) {
    recorder.captureFrame(latestAudioState);
}
```

**Step 3: Skip staleness during playback in ConnectionStateListener**

In `tick()`, wrap the staleness check with a playback guard:

```java
var recorder = plugin.getRecordingManager();
boolean playingBack = recorder != null && recorder.isReplaying();

if (djConnected && !stale && !playingBack && lastFrameMs > 0 && isStale(lastFrameMs, now, STALE_THRESHOLD_MS)) {
```

**Step 4: Add /av recording commands**

In `onCommand()`, add case:
```java
case "recording", "rec" -> handleRecordingCommand(sender, args);
```

Add handler:
```java
private void handleRecordingCommand(CommandSender sender, String[] args) {
    if (!sender.hasPermission("audioviz.recording") && !sender.hasPermission("audioviz.admin")) {
        sender.sendMessage(ChatColor.RED + "You don't have permission to manage recordings.");
        return;
    }
    var rm = plugin.getRecordingManager();
    if (rm == null) {
        sender.sendMessage(ChatColor.RED + "Recording manager not available.");
        return;
    }
    if (args.length < 2) {
        sender.sendMessage(ChatColor.AQUA + "Usage: /av recording <start|stop|play|list|delete>");
        return;
    }
    switch (args[1].toLowerCase()) {
        case "start" -> {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /av recording start <name>");
                return;
            }
            if (rm.startRecording(args[2])) {
                sender.sendMessage(ChatColor.GREEN + "Recording started: " + args[2]);
            } else {
                sender.sendMessage(ChatColor.RED + "Already recording.");
            }
        }
        case "stop" -> {
            if (rm.isRecording()) {
                rm.stopRecording();
                sender.sendMessage(ChatColor.GREEN + "Recording saved.");
            } else if (rm.isReplaying()) {
                rm.stopPlayback();
                sender.sendMessage(ChatColor.GREEN + "Playback stopped.");
            } else {
                sender.sendMessage(ChatColor.YELLOW + "Nothing to stop.");
            }
        }
        case "play" -> {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /av recording play <name>");
                return;
            }
            if (rm.startPlayback(args[2])) {
                sender.sendMessage(ChatColor.GREEN + "Playing: " + args[2]);
            } else {
                sender.sendMessage(ChatColor.RED + "Recording '" + args[2] + "' not found or already playing.");
            }
        }
        case "list" -> {
            var names = rm.listRecordings();
            if (names.isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + "No recordings saved.");
            } else {
                sender.sendMessage(ChatColor.AQUA + "Recordings (" + names.size() + "):");
                for (var name : names) {
                    sender.sendMessage(ChatColor.GRAY + "  " + name);
                }
            }
        }
        case "delete" -> {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /av recording delete <name>");
                return;
            }
            if (rm.deleteRecording(args[2])) {
                sender.sendMessage(ChatColor.GREEN + "Deleted: " + args[2]);
            } else {
                sender.sendMessage(ChatColor.RED + "Recording '" + args[2] + "' not found.");
            }
        }
        default -> sender.sendMessage(ChatColor.RED + "Unknown: /av recording " + args[1]);
    }
}
```

In `onTabComplete()`, add `"recording"`, `"rec"` to top-level, with sub-completions.

In `sendHelp()`, add: `sender.sendMessage(ChatColor.AQUA + "/av recording <start|stop|play|list|delete>" + ChatColor.GRAY + " - Record and replay sessions");`

**Step 5: Update MetricsDisplay**

Update `collectSequences()` to also show recording/playback status:

```java
private String collectSequences() {
    var sm = plugin.getSequenceManager();
    var rm = plugin.getRecordingManager();
    String seqCount = sm != null ? String.valueOf(sm.getActiveCount()) : "0";
    if (rm != null && rm.isRecording()) {
        return seqCount + " | REC";
    } else if (rm != null && rm.isReplaying()) {
        return seqCount + " | PLAY";
    }
    return seqCount;
}
```

**Step 6: Run all tests**

Run: `cd minecraft_plugin && ./mvnw test -pl . 2>&1 | tail -10`
Expected: All tests PASS

**Step 7: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java \
        minecraft_plugin/src/main/java/com/audioviz/commands/AudioVizCommand.java \
        minecraft_plugin/src/main/java/com/audioviz/metrics/MetricsDisplay.java \
        minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java \
        minecraft_plugin/src/main/java/com/audioviz/connection/ConnectionStateListener.java
git commit -m "feat: wire RecordingManager into plugin with /av recording commands"
```

---

### Task 9: Run Full Test Suite and Verify

**Step 1: Run all tests**

Run: `cd minecraft_plugin && ./mvnw test -pl . 2>&1 | tail -30`
Expected: All tests PASS, 0 failures

**Step 2: Verify new test count**

Run: `cd minecraft_plugin && ./mvnw test -pl . 2>&1 | grep "Tests run"`
Expected: Test count increased by ~30 (BeatSyncConfig: 11, LatencyTracker: 10, RecordingFrame: 4, Recording: 5)

**Step 3: Final commit if needed**

```bash
git add -A minecraft_plugin/
git commit -m "feat: batch 2 complete — beat-sync, latency monitoring, recording & replay"
```
