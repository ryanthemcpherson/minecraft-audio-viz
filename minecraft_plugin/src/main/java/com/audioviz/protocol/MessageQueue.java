package com.audioviz.protocol;

import com.audioviz.AudioVizPlugin;
import com.audioviz.effects.BeatType;
import com.audioviz.entities.EntityUpdate;
import com.audioviz.patterns.AudioState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * High-performance message queue for WebSocket messages.
 *
 * Features:
 * - Async JSON parsing on dedicated thread pool (off main thread)
 * - Tick-based batch processing (one scheduler call per tick)
 * - Entity update batching for efficient rendering
 * - Backpressure: drops oldest messages when queue is full
 */
public class MessageQueue {

    private final AudioVizPlugin plugin;
    private final MessageHandler messageHandler;

    // Queue for parsed JSON messages
    private final ConcurrentLinkedQueue<JsonObject> messageQueue;

    // Queue for batched entity updates (collected across messages)
    private final ConcurrentLinkedQueue<EntityUpdate> entityUpdateQueue;

    // Dedicated thread pool for JSON parsing
    private final ExecutorService jsonExecutor;

    // Tick processor task
    private BukkitTask processorTask;

    // Stats (atomic for thread-safe access)
    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong batchesSent = new AtomicLong(0);
    private final AtomicLong messagesDropped = new AtomicLong(0);
    private final Map<String, Long> lastBeatTimestampByZone = new ConcurrentHashMap<>();

    // Backpressure limit
    private static final int MAX_QUEUE_SIZE = 1000;

    public MessageQueue(AudioVizPlugin plugin, MessageHandler messageHandler) {
        this.plugin = plugin;
        this.messageHandler = messageHandler;
        this.messageQueue = new ConcurrentLinkedQueue<>();
        this.entityUpdateQueue = new ConcurrentLinkedQueue<>();
        this.jsonExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "AudioViz-JSON-Parser");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the tick-based message processor.
     */
    public void start() {
        // Process queue every tick (50ms = 20 TPS)
        processorTask = Bukkit.getScheduler().runTaskTimer(plugin, this::processTick, 1L, 1L);
        plugin.getLogger().info("MessageQueue started (tick-based processing)");
    }

    /**
     * Stop the message processor.
     */
    public void stop() {
        if (processorTask != null) {
            processorTask.cancel();
            processorTask = null;
        }
        jsonExecutor.shutdown();
        try {
            // Wait up to 5 seconds for pending JSON parsing to complete
            if (!jsonExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                jsonExecutor.shutdownNow();
                plugin.getLogger().warning("MessageQueue JSON executor did not terminate gracefully");
            }
        } catch (InterruptedException e) {
            jsonExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        long dropped = messagesDropped.get();
        plugin.getLogger().info("MessageQueue stopped. Processed " + messagesProcessed.get() +
                " messages in " + batchesSent.get() + " batches" +
                (dropped > 0 ? " (" + dropped + " dropped)" : ""));
    }

    /**
     * Enqueue a raw JSON string for async parsing and processing.
     * Called from WebSocket thread - must be thread-safe and non-blocking.
     *
     * If the queue is full, the oldest message is dropped to apply backpressure.
     */
    public void enqueueRaw(String rawJson) {
        jsonExecutor.submit(() -> {
            try {
                JsonObject json = JsonParser.parseString(rawJson).getAsJsonObject();
                if (messageQueue.size() >= MAX_QUEUE_SIZE) {
                    messageQueue.poll(); // Drop oldest
                    long dropped = messagesDropped.incrementAndGet();
                    if (dropped % 100 == 1) {
                        plugin.getLogger().warning("MessageQueue backpressure: dropped message (total dropped: " + dropped + ")");
                    }
                }
                messageQueue.offer(json);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to parse JSON message", e);
            }
        });
    }

    /**
     * Enqueue an already-parsed JSON object.
     */
    public void enqueue(JsonObject json) {
        if (messageQueue.size() >= MAX_QUEUE_SIZE) {
            messageQueue.poll(); // Drop oldest
            messagesDropped.incrementAndGet();
        }
        messageQueue.offer(json);
    }

    /**
     * Process all queued messages for this tick.
     * Called on main thread every tick.
     */
    private void processTick() {
        // Collect entity updates per zone (supports multi-zone messages)
        Map<String, List<EntityUpdate>> updatesByZone = new java.util.HashMap<>();
        // Coalesce high-frequency frame messages: keep only newest batch_update per zone.
        Map<String, JsonObject> latestBatchByZone = new java.util.HashMap<>();

        // Process all queued messages
        JsonObject msg;
        while ((msg = messageQueue.poll()) != null) {
            messagesProcessed.incrementAndGet();

            String type = msg.has("type") ? msg.get("type").getAsString() : "unknown";

            // Handle batch_update specially for performance
            if ("batch_update".equals(type)) {
                // Keep only the latest frame for each zone to avoid replaying stale updates.
                String zoneName = msg.has("zone") ? msg.get("zone").getAsString() : "main";
                JsonObject replaced = latestBatchByZone.put(zoneName, msg);
                if (replaced != null) {
                    messagesDropped.incrementAndGet();
                }
            } else {
                // Process other message types through normal handler
                try {
                    messageHandler.handleMessage(type, msg);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Error handling message type: " + type, e);
                }
            }
        }

        // Process only the freshest batch_update per zone this tick.
        for (Map.Entry<String, JsonObject> entry : latestBatchByZone.entrySet()) {
            String zoneName = entry.getKey();
            JsonObject batch = entry.getValue();
            List<EntityUpdate> zoneUpdates = updatesByZone.computeIfAbsent(zoneName, k -> new ArrayList<>());
            extractEntityUpdates(batch, zoneName, zoneUpdates);
            processAudioInfo(batch, zoneName);
        }

        // Also drain the entity update queue (these are from direct enqueue calls)
        EntityUpdate update;
        while ((update = entityUpdateQueue.poll()) != null) {
            // EntityUpdate doesn't have zone info, use "main" as default
            updatesByZone.computeIfAbsent("main", k -> new ArrayList<>()).add(update);
        }

        // Send entity updates per zone
        for (Map.Entry<String, List<EntityUpdate>> entry : updatesByZone.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                plugin.getEntityPoolManager().batchUpdateEntities(entry.getKey(), entry.getValue());
                batchesSent.incrementAndGet();
            }
        }
    }

    /**
     * Extract entity updates from a batch_update message.
     */
    private void extractEntityUpdates(JsonObject msg, String zoneName, List<EntityUpdate> updates) {
        if (!msg.has("entities")) return;

        // Skip entity updates if zone is in particle-only mode
        if (!plugin.getParticleVisualizationManager().shouldRenderEntities(zoneName)) {
            return;
        }

        JsonArray entities = msg.getAsJsonArray("entities");
        var zone = plugin.getZoneManager().getZone(zoneName);
        if (zone == null) return;

        for (JsonElement elem : entities) {
            JsonObject entity = elem.getAsJsonObject();

            String entityId = entity.has("id") ? entity.get("id").getAsString() : null;
            if (entityId == null) continue;

            // Parse normalized coordinates (0-1, clamped for safety) and convert to world coordinates
            double nx = InputSanitizer.sanitizeCoordinate(
                entity.has("x") ? entity.get("x").getAsDouble() : 0.5);
            double ny = InputSanitizer.sanitizeCoordinate(
                entity.has("y") ? entity.get("y").getAsDouble() : 0.5);
            double nz = InputSanitizer.sanitizeCoordinate(
                entity.has("z") ? entity.get("z").getAsDouble() : 0.5);

            // Convert to world coordinates using zone's localToWorld (respects rotation)
            Location loc = zone.localToWorld(nx, ny, nz);

            // Parse scale and rotation if present (clamped for safety)
            float scale = InputSanitizer.sanitizeScale(
                entity.has("scale") ? entity.get("scale").getAsFloat() : 0.5f);
            float rotationY = InputSanitizer.sanitizeRotation(
                entity.has("rotation") ? entity.get("rotation").getAsFloat() : 0f);

            // Rotation-aware pivot: the block model spans [0,s] after scaling.
            // LeftRotation rotates the scaled model around (0,0,0), shifting its
            // center away from (s/2, s/2, s/2). Compute the translation that puts
            // the rotated center back at (0.5, 0.5, 0.5) within the unit cell.
            float halfScale = scale * 0.5f;
            float rotRad = (float) Math.toRadians(rotationY);
            float cosR = (float) Math.cos(rotRad);
            float sinR = (float) Math.sin(rotRad);
            float pivotX = 0.5f - halfScale * (cosR + sinR);
            float pivotY = 0.5f - halfScale;
            float pivotZ = 0.5f - halfScale * (cosR - sinR);

            // Create transformation with rotation
            Transformation transform = new Transformation(
                new Vector3f(pivotX, pivotY, pivotZ),
                new AxisAngle4f(rotRad, 0, 1, 0),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f(0, 0, 0, 1)
            );

            // Build update with optional brightness, glow, and interpolation
            EntityUpdate.Builder builder = EntityUpdate.builder(entityId)
                .location(loc)
                .transformation(transform);

            // Parse brightness if present (clamped to 0-15)
            if (entity.has("brightness")) {
                builder.brightness(InputSanitizer.sanitizeBrightness(entity.get("brightness").getAsInt()));
            }

            // Parse glow if present
            if (entity.has("glow")) {
                builder.glow(entity.get("glow").getAsBoolean());
            }

            // Parse per-entity interpolation duration if present (clamped to 0-100)
            if (entity.has("interpolation")) {
                builder.interpolationDuration(InputSanitizer.sanitizeInterpolation(entity.get("interpolation").getAsInt()));
            }

            updates.add(builder.build());
        }
    }

    /**
     * Process audio/beat information from a batch_update message.
     * Triggers beat effects, glow_on_beat, dynamic_brightness, and updates particle visualization.
     */
    private void processAudioInfo(JsonObject msg, String zoneName) {
        boolean explicitBeat = msg.has("is_beat") && msg.get("is_beat").getAsBoolean();
        double explicitBeatIntensity = InputSanitizer.sanitizeDouble(
            msg.has("beat_intensity") ? msg.get("beat_intensity").getAsDouble() : 0.0,
            0.0, 1.0, 0.0);
        double bpm = InputSanitizer.sanitizeDouble(
            msg.has("bpm") ? msg.get("bpm").getAsDouble() : 0.0,
            0.0, 300.0, 0.0);
        double tempoConfidence = InputSanitizer.sanitizeDouble(
            msg.has("tempo_confidence") ? msg.get("tempo_confidence").getAsDouble()
                : (msg.has("tempo_conf") ? msg.get("tempo_conf").getAsDouble() : 0.0),
            0.0, 1.0, 0.0);
        double beatPhase = InputSanitizer.sanitizeDouble(
            msg.has("beat_phase") ? msg.get("beat_phase").getAsDouble() : 0.0,
            0.0, 1.0, 0.0);

        BeatProjectionUtil.BeatProjection projection = BeatProjectionUtil.projectBeat(
            zoneName, explicitBeat, explicitBeatIntensity, bpm, tempoConfidence, beatPhase,
            lastBeatTimestampByZone);
        boolean isBeat = projection.isBeat();
        double beatIntensity = projection.beatIntensity();

        // Trigger beat effects if this is a beat with sufficient intensity
        if (isBeat && beatIntensity > 0.2) {
            plugin.getBeatEventManager().processBeat(zoneName, BeatType.BEAT, beatIntensity);
        }

        // Apply zone-level glow_on_beat and dynamic_brightness settings
        var zone = plugin.getZoneManager().getZone(zoneName);
        if (zone != null) {
            // Glow on beat: flash glow for all entities when beat detected
            if (zone.isGlowOnBeat() && isBeat && beatIntensity > 0.3) {
                plugin.getEntityPoolManager().setZoneGlow(zoneName, true);
                // Schedule glow off after 3 ticks (150ms)
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    plugin.getEntityPoolManager().setZoneGlow(zoneName, false);
                }, 3L);
            }

            // Dynamic brightness: scale brightness with audio amplitude
            if (zone.isDynamicBrightness()) {
                double amplitude = InputSanitizer.sanitizeAmplitude(
                    msg.has("amplitude") ? msg.get("amplitude").getAsDouble() : 0.0);
                // Map amplitude (0-1) to brightness (3-15)
                int brightness = (int) Math.round(3 + amplitude * 12);
                brightness = Math.max(3, Math.min(15, brightness));
                plugin.getEntityPoolManager().setZoneBrightness(zoneName, brightness);
            }
        }

        // Update particle visualization audio state
        if (msg.has("bands")) {
            JsonArray bandsJson = msg.getAsJsonArray("bands");
            int bandCount = Math.min(bandsJson.size(), 10); // Cap array size
            double[] bands = new double[bandCount];
            for (int i = 0; i < bands.length; i++) {
                bands[i] = InputSanitizer.sanitizeBandValue(bandsJson.get(i).getAsDouble());
            }
            double amplitude = InputSanitizer.sanitizeAmplitude(
                msg.has("amplitude") ? msg.get("amplitude").getAsDouble() : 0.0);
            long frame = msg.has("frame") ? msg.get("frame").getAsLong() : messagesProcessed.get();

            AudioState audioState = new AudioState(
                bands, amplitude, isBeat, beatIntensity, tempoConfidence, beatPhase, frame);
            plugin.getParticleVisualizationManager().updateAudioState(audioState);
        }
    }

    /**
     * Get processing statistics.
     */
    public String getStats() {
        long dropped = messagesDropped.get();
        return String.format("Messages: %d, Batches: %d, Queue: %d%s",
                messagesProcessed.get(), batchesSent.get(), messageQueue.size(),
                dropped > 0 ? ", Dropped: " + dropped : "");
    }
}
