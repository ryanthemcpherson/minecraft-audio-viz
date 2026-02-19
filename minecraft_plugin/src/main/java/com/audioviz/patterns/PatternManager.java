package com.audioviz.patterns;

import com.audioviz.AudioVizPlugin;
import com.audioviz.patterns.impl.*;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Manages visualization patterns - registration, switching, and execution.
 */
public class PatternManager {

    private final AudioVizPlugin plugin;
    private final Logger logger;

    /** All registered patterns by ID */
    private final Map<String, VisualizationPattern> patterns = new LinkedHashMap<>();

    /** Active pattern per zone */
    private final Map<String, String> activePatterns = new ConcurrentHashMap<>();

    /** Running animation tasks per zone */
    private final Map<String, BukkitTask> runningTasks = new ConcurrentHashMap<>();

    /** Current audio state (updated from WebSocket) */
    private AudioState currentAudioState = AudioState.silent();

    /** Frame counter for animations */
    private long frameCounter = 0;

    public PatternManager(AudioVizPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        registerDefaultPatterns();
    }

    /**
     * Register all built-in patterns.
     */
    private void registerDefaultPatterns() {
        registerPattern(new SpectrumBars());
        registerPattern(new EQBars());
        registerPattern(new SpiralTower());
        registerPattern(new PulseSphere());
        registerPattern(new DNAHelix());
        registerPattern(new Supernova());
        registerPattern(new WaveRing());
        registerPattern(new Fountain());
        registerPattern(new RotatingCube());

        logger.info("Registered " + patterns.size() + " visualization patterns");
    }

    /**
     * Register a pattern.
     */
    public void registerPattern(VisualizationPattern pattern) {
        patterns.put(pattern.getId(), pattern);
    }

    /**
     * Get a pattern by ID.
     */
    public VisualizationPattern getPattern(String id) {
        return patterns.get(id);
    }

    /**
     * Get all registered patterns.
     */
    public Collection<VisualizationPattern> getAllPatterns() {
        return patterns.values();
    }

    /**
     * Get pattern IDs.
     */
    public Set<String> getPatternIds() {
        return patterns.keySet();
    }

    /**
     * Get the active pattern for a zone.
     */
    public String getActivePattern(String zoneName) {
        return activePatterns.get(zoneName);
    }

    /**
     * Set the active pattern for a zone.
     */
    public void setActivePattern(String zoneName, String patternId) {
        if (!patterns.containsKey(patternId)) {
            logger.warning("Unknown pattern: " + patternId);
            return;
        }

        activePatterns.put(zoneName, patternId);
        logger.info("Set pattern '" + patternId + "' for zone '" + zoneName + "'");

        // Reset the pattern state
        VisualizationPattern pattern = patterns.get(patternId);
        if (pattern != null) {
            pattern.reset();
        }
    }

    /**
     * Update the current audio state (called from WebSocket handler).
     */
    public void updateAudioState(AudioState state) {
        this.currentAudioState = state;
        this.frameCounter++;
    }

    /**
     * Get the current audio state.
     */
    public AudioState getCurrentAudioState() {
        return currentAudioState;
    }

    /**
     * Start a test animation for a zone.
     * Runs for specified duration with simulated audio.
     */
    public void startTestAnimation(String zoneName, String patternId, int durationTicks) {
        // Stop any existing animation
        stopAnimation(zoneName);

        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
        if (zone == null) {
            logger.warning("Zone not found: " + zoneName);
            return;
        }

        VisualizationPattern pattern = patterns.get(patternId);
        if (pattern == null) {
            logger.warning("Pattern not found: " + patternId);
            return;
        }

        int entityCount = plugin.getEntityPoolManager().getEntityCount(zoneName);
        if (entityCount == 0) {
            logger.warning("No entities in zone: " + zoneName);
            return;
        }

        // Configure pattern for this zone's entity count
        pattern.setConfig(new PatternConfig(entityCount));
        pattern.reset();

        final long[] frame = {0};
        final int[] beatFrame = {0};

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            // Generate test audio state
            boolean isBeat = (frame[0] % 20 == 0); // Beat every 20 ticks (1 second)
            AudioState testAudio = AudioState.forTest(frame[0], isBeat);

            // Calculate entity positions
            List<EntityUpdate> updates = pattern.calculate(testAudio);

            // Apply updates to entities
            applyUpdates(zoneName, zone, updates);

            frame[0]++;

            // Stop after duration
            if (frame[0] >= durationTicks) {
                stopAnimation(zoneName);
            }
        }, 0L, 1L); // Run every tick

        runningTasks.put(zoneName, task);
        logger.info("Started test animation '" + patternId + "' in zone '" + zoneName + "'");
    }

    /**
     * Start continuous pattern playback for a zone (audio-reactive).
     */
    public void startPattern(String zoneName, String patternId) {
        stopAnimation(zoneName);

        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
        if (zone == null) {
            logger.warning("Zone not found: " + zoneName);
            return;
        }

        VisualizationPattern pattern = patterns.get(patternId);
        if (pattern == null) {
            logger.warning("Pattern not found: " + patternId);
            return;
        }

        int entityCount = plugin.getEntityPoolManager().getEntityCount(zoneName);
        if (entityCount == 0) {
            logger.warning("No entities in zone: " + zoneName);
            return;
        }

        // Configure pattern
        pattern.setConfig(new PatternConfig(entityCount));
        pattern.reset();
        activePatterns.put(zoneName, patternId);

        // Start animation loop (runs until stopped)
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            // Use current audio state from WebSocket
            List<EntityUpdate> updates = pattern.calculate(currentAudioState);
            applyUpdates(zoneName, zone, updates);
        }, 0L, 1L);

        runningTasks.put(zoneName, task);
        logger.info("Started pattern '" + patternId + "' in zone '" + zoneName + "'");
    }

    /**
     * Stop animation for a zone.
     */
    public void stopAnimation(String zoneName) {
        BukkitTask task = runningTasks.remove(zoneName);
        if (task != null) {
            task.cancel();
            logger.info("Stopped animation in zone '" + zoneName + "'");
        }
    }

    /**
     * Stop all animations.
     */
    public void stopAllAnimations() {
        for (String zoneName : new ArrayList<>(runningTasks.keySet())) {
            stopAnimation(zoneName);
        }
    }

    /**
     * Apply entity updates to the zone.
     * All updates are applied in a single batch for efficiency.
     */
    private void applyUpdates(String zoneName, VisualizationZone zone, List<EntityUpdate> updates) {
        // Already on main thread when called from scheduler, just apply directly
        for (EntityUpdate update : updates) {
            // Convert local coordinates (0-1) to world coordinates
            Location worldLoc = zone.localToWorld(update.getX(), update.getY(), update.getZ());

            // Get and update entity (convert int id to string)
            Entity entity = plugin.getEntityPoolManager().getEntity(zoneName, "block_" + update.getId());
            if (entity != null) {
                entity.teleport(worldLoc);

                // Update scale if it's a display entity
                if (entity instanceof org.bukkit.entity.Display display) {
                    float scale = (float) update.getScale();
                    float rotY = (float) update.getRotation();
                    display.setTransformation(new org.bukkit.util.Transformation(
                        new org.joml.Vector3f(0, 0, 0),
                        new org.joml.AxisAngle4f((float) Math.toRadians(rotY), 0, 1, 0),
                        new org.joml.Vector3f(scale, scale, scale),
                        new org.joml.AxisAngle4f(0, 0, 0, 1)
                    ));
                }
            }
        }
    }

    /**
     * Calculate pattern once and return updates (for WebSocket-driven mode).
     */
    public List<EntityUpdate> calculatePattern(String zoneName, String patternId, AudioState audio) {
        VisualizationPattern pattern = patterns.get(patternId);
        if (pattern == null) {
            return Collections.emptyList();
        }

        int entityCount = plugin.getEntityPoolManager().getEntityCount(zoneName);
        if (entityCount == 0) {
            return Collections.emptyList();
        }

        // Ensure pattern has correct config
        if (pattern.getConfig().getEntityCount() != entityCount) {
            pattern.setConfig(new PatternConfig(entityCount));
        }

        return pattern.calculate(audio);
    }

    /**
     * Check if a zone has a running animation.
     */
    public boolean isAnimationRunning(String zoneName) {
        return runningTasks.containsKey(zoneName);
    }

    /**
     * Get info about a pattern.
     */
    public Map<String, Object> getPatternInfo(String patternId) {
        VisualizationPattern pattern = patterns.get(patternId);
        if (pattern == null) {
            return null;
        }

        Map<String, Object> info = new HashMap<>();
        info.put("id", pattern.getId());
        info.put("name", pattern.getName());
        info.put("description", pattern.getDescription());
        return info;
    }

    /**
     * Get list of all patterns with info.
     */
    public List<Map<String, Object>> listPatterns() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (VisualizationPattern pattern : patterns.values()) {
            Map<String, Object> info = new HashMap<>();
            info.put("id", pattern.getId());
            info.put("name", pattern.getName());
            info.put("description", pattern.getDescription());
            list.add(info);
        }
        return list;
    }

    /**
     * Cleanup resources.
     */
    public void shutdown() {
        stopAllAnimations();
    }
}
