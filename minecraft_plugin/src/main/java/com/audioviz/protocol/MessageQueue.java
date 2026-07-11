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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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

    @FunctionalInterface
    public interface MessageGuard {
        /** Runs {@code operation} atomically with the guard check when still valid. */
        boolean runIfValid(Runnable operation);
    }

    private static final MessageGuard ALLOW_ALL = operation -> {
        operation.run();
        return true;
    };
    private static final Runnable NO_OP = () -> { };

    private final AudioVizPlugin plugin;
    private final MessageHandler messageHandler;

    // Queue for parsed JSON messages
    private final ArrayBlockingQueue<QueuedMessage> messageQueue;

    // Queue for batched entity updates (collected across messages)
    private final ConcurrentLinkedQueue<EntityUpdate> entityUpdateQueue;

    // Dedicated thread pool for JSON parsing
    private final ThreadPoolExecutor jsonExecutor;
    private final Object intakeLock = new Object();
    private boolean acceptingMessages = true;

    // Tick processor task
    private BukkitTask processorTask;

    // Stats (atomic for thread-safe access)
    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong batchesSent = new AtomicLong(0);
    private final AtomicLong messagesDropped = new AtomicLong(0);
    private final Map<String, Long> lastBeatTimestampByZone = new ConcurrentHashMap<>();

    // Backpressure limit
    private static final int JSON_PARSER_THREADS = 2;
    private static final int MAX_RAW_QUEUE_SIZE = 64;
    private static final int MAX_QUEUE_SIZE = 1000;

    // Pre-allocated identity rotation (immutable, safe to share across all entities).
    // Transformation constructor copies via new Quaternionf(axisAngle), so sharing is safe.
    private static final AxisAngle4f IDENTITY_ROTATION = new AxisAngle4f(0, 0, 0, 1);

    // Trig cache for batch: maps rotation float bits to packed cos/sin long.
    // Cleared each tick. Uses Float.floatToRawIntBits as key for exact float matching.
    // Packed format: upper 32 bits = cos float bits, lower 32 bits = sin float bits.
    private final HashMap<Integer, Long> trigCache = new HashMap<>(64);

    // Reusable per-tick maps — cleared each tick instead of re-allocated.
    private final HashMap<String, List<EntityUpdate>> updatesByZone = new HashMap<>(4);
    private final HashMap<String, ArrayDeque<QueuedMessage>> batchCandidatesByZone =
        new HashMap<>(4);
    private final HashMap<String, ArrayDeque<QueuedMessage>> bitmapCandidatesByZone =
        new HashMap<>(4);

    // Shared lambda for computeIfAbsent to avoid per-call lambda allocation
    private static final java.util.function.Function<String, List<EntityUpdate>> NEW_UPDATE_LIST = k -> new ArrayList<>();

    // PERFORMANCE: Scratch JOML objects reused per entity in extractEntityUpdates.
    // Transformation constructor copies these internally, so mutating between calls is safe.
    // This avoids 3 object allocations per entity per tick (60k allocs/sec at 1000 entities).
    private final Vector3f scratchTranslation = new Vector3f();
    private final Vector3f scratchScale = new Vector3f();
    private final AxisAngle4f scratchLeftRotation = new AxisAngle4f();

    public MessageQueue(AudioVizPlugin plugin, MessageHandler messageHandler) {
        this.plugin = plugin;
        this.messageHandler = messageHandler;
        this.messageQueue = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);
        this.entityUpdateQueue = new ConcurrentLinkedQueue<>();
        this.jsonExecutor = new ThreadPoolExecutor(
            JSON_PARSER_THREADS,
            JSON_PARSER_THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_RAW_QUEUE_SIZE),
            runnable -> {
                Thread thread = new Thread(runnable, "AudioViz-JSON-Parser");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
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
        synchronized (intakeLock) {
            acceptingMessages = false;
            jsonExecutor.shutdown();
        }
        boolean interrupted = false;
        boolean forcedShutdownLogged = false;
        while (!jsonExecutor.isTerminated()) {
            try {
                if (jsonExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    break;
                }
                discardQueuedParserTasks();
                if (!forcedShutdownLogged) {
                    plugin.getLogger().warning(
                        "MessageQueue JSON executor did not terminate gracefully"
                    );
                    forcedShutdownLogged = true;
                }
            } catch (InterruptedException e) {
                interrupted = true;
                discardQueuedParserTasks();
            }
        }

        // Parser termination is a hard boundary: no task can repopulate these
        // queues after the session has been invalidated.
        messageQueue.clear();
        entityUpdateQueue.clear();
        batchCandidatesByZone.clear();
        bitmapCandidatesByZone.clear();
        updatesByZone.values().forEach(List::clear);
        updatesByZone.clear();
        trigCache.clear();
        lastBeatTimestampByZone.clear();

        if (interrupted) {
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
        enqueueRaw(rawJson, ALLOW_ALL);
    }

    /**
     * Enqueue raw JSON with a guard that remains attached through parsing and execution.
     */
    public void enqueueRaw(String rawJson, MessageGuard guard) {
        Runnable parseTask = () -> {
            try {
                if (!guard.runIfValid(NO_OP)) {
                    messagesDropped.incrementAndGet();
                    return;
                }
                JsonObject json = JsonParser.parseString(rawJson).getAsJsonObject();
                offer(new QueuedMessage(json, guard));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to parse JSON message", e);
            }
        };

        synchronized (intakeLock) {
            if (!acceptingMessages) {
                recordDroppedMessage();
                return;
            }
            try {
                jsonExecutor.execute(parseTask);
            } catch (RejectedExecutionException exception) {
                recordDroppedMessage();
            }
        }
    }

    /**
     * Enqueue an already-parsed JSON object.
     */
    public void enqueue(JsonObject json) {
        enqueue(json, ALLOW_ALL);
    }

    /**
     * Enqueue parsed JSON with a guard checked atomically around handler execution.
     */
    public void enqueue(JsonObject json, MessageGuard guard) {
        synchronized (intakeLock) {
            if (!acceptingMessages) {
                recordDroppedMessage();
                return;
            }
            offer(new QueuedMessage(json, guard));
        }
    }

    private void offer(QueuedMessage message) {
        synchronized (messageQueue) {
            if (messageQueue.offer(message)) {
                return;
            }

            QueuedMessage discarded = messageQueue.poll();
            if (!messageQueue.offer(message)) {
                recordDroppedMessage();
                return;
            }
            if (discarded != null) {
                recordDroppedMessage();
            }
        }
    }

    private void discardQueuedParserTasks() {
        int discardedTasks = jsonExecutor.shutdownNow().size();
        if (discardedTasks > 0) {
            messagesDropped.addAndGet(discardedTasks);
        }
    }

    private void recordDroppedMessage() {
        long dropped = messagesDropped.incrementAndGet();
        if (dropped % 100 == 1) {
            plugin.getLogger().warning(
                "MessageQueue backpressure: dropped message (total dropped: " + dropped + ")"
            );
        }
    }

    /**
     * Process all queued messages for this tick.
     * Called on main thread every tick.
     */
    private void processTick() {
        // Clear per-tick caches and reusable maps
        trigCache.clear();
        updatesByZone.values().forEach(List::clear);
        batchCandidatesByZone.clear();
        bitmapCandidatesByZone.clear();

        // Process all queued messages
        QueuedMessage queuedMessage;
        while ((queuedMessage = messageQueue.poll()) != null) {
            messagesProcessed.incrementAndGet();

            QueuedMessage current = queuedMessage;
            try {
                boolean accepted = current.guard().runIfValid(
                    () -> classifyMessage(current)
                );
                if (!accepted) {
                    messagesDropped.incrementAndGet();
                }
            } catch (Exception e) {
                messagesDropped.incrementAndGet();
                plugin.getLogger().log(Level.WARNING, "Error handling queued message", e);
            }
        }

        processBatchCandidates();
        processBitmapCandidates();

        // Also drain the entity update queue (these are from direct enqueue calls)
        EntityUpdate update;
        while ((update = entityUpdateQueue.poll()) != null) {
            // EntityUpdate doesn't have zone info, use "main" as default
            updatesByZone.computeIfAbsent("main", NEW_UPDATE_LIST).add(update);
        }

        // Send entity updates per zone
        for (Map.Entry<String, List<EntityUpdate>> entry : updatesByZone.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                plugin.getEntityPoolManager().batchUpdateEntities(entry.getKey(), entry.getValue());
                batchesSent.incrementAndGet();
            }
        }
    }

    private void classifyMessage(QueuedMessage queuedMessage) {
        JsonObject message = queuedMessage.message();
        String type = message.has("type") ? message.get("type").getAsString() : "unknown";

        if ("batch_update".equals(type)) {
            String zoneName = message.has("zone") ? message.get("zone").getAsString() : "main";
            batchCandidatesByZone
                .computeIfAbsent(zoneName, ignored -> new ArrayDeque<>())
                .addLast(queuedMessage);
        } else if ("bitmap_frame".equals(type)) {
            String zoneName = message.has("zone") ? message.get("zone").getAsString() : "main";
            bitmapCandidatesByZone
                .computeIfAbsent(zoneName, ignored -> new ArrayDeque<>())
                .addLast(queuedMessage);
        } else {
            messageHandler.handleMessage(type, message);
        }
    }

    private void processBatchCandidates() {
        for (Map.Entry<String, ArrayDeque<QueuedMessage>> entry : batchCandidatesByZone.entrySet()) {
            String zoneName = entry.getKey();
            ArrayDeque<QueuedMessage> candidates = entry.getValue();
            int candidateCount = candidates.size();
            boolean selected = false;

            QueuedMessage candidate;
            while ((candidate = candidates.pollLast()) != null) {
                QueuedMessage current = candidate;
                try {
                    boolean executed = current.guard().runIfValid(() -> {
                        JsonObject batch = current.message();
                        List<EntityUpdate> zoneUpdates = updatesByZone.computeIfAbsent(
                            zoneName,
                            NEW_UPDATE_LIST
                        );
                        try {
                            extractEntityUpdates(batch, zoneName, zoneUpdates);
                            processAudioInfo(batch, zoneName);
                            if (!zoneUpdates.isEmpty()) {
                                plugin.getEntityPoolManager().batchUpdateEntities(
                                    zoneName,
                                    zoneUpdates
                                );
                                batchesSent.incrementAndGet();
                            }
                        } finally {
                            // Never let an invalidated generation leak extracted updates
                            // into the unguarded direct-update drain below.
                            zoneUpdates.clear();
                        }
                    });
                    if (executed) {
                        selected = true;
                        break;
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Error handling message type: batch_update", e);
                    break;
                }
            }

            messagesDropped.addAndGet(selected ? candidateCount - 1L : candidateCount);
        }
    }

    private void processBitmapCandidates() {
        for (ArrayDeque<QueuedMessage> candidates : bitmapCandidatesByZone.values()) {
            int candidateCount = candidates.size();
            boolean selected = false;

            QueuedMessage candidate;
            while ((candidate = candidates.pollLast()) != null) {
                QueuedMessage current = candidate;
                try {
                    boolean executed = current.guard().runIfValid(
                        () -> messageHandler.handleMessage("bitmap_frame", current.message())
                    );
                    if (executed) {
                        selected = true;
                        break;
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Error handling message type: bitmap_frame", e);
                    break;
                }
            }

            messagesDropped.addAndGet(selected ? candidateCount - 1L : candidateCount);
        }
    }

    private record QueuedMessage(JsonObject message, MessageGuard guard) { }

    /**
     * Extract entity updates from a batch_update message.
     *
     * Performance: this is the hottest path in the plugin, running for up to 1000
     * entities every server tick (50ms). Optimizations applied:
     * - Trig cache: cos/sin looked up by rotation float-bits, avoiding redundant Math.cos/sin
     * - Shared identity AxisAngle4f: single static instance for right-rotation (always identity)
     * - Fast-path for rotation==0: skip trig entirely, use simple centered pivot
     * - Pre-sized ArrayList: avoids incremental resizing during entity iteration
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

        // Pre-size to avoid ArrayList resizing (entities.size() is O(1) for JsonArray)
        if (updates instanceof ArrayList<EntityUpdate> arrayList) {
            arrayList.ensureCapacity(updates.size() + entities.size());
        }

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

            // Check visibility — if hidden, force scale to 0 (handled in the same transform)
            boolean visible = !entity.has("visible") || entity.get("visible").getAsBoolean();

            // Parse scale and rotation if present (clamped for safety)
            float scale = !visible ? 0f
                : InputSanitizer.sanitizeScale(
                    entity.has("scale") ? entity.get("scale").getAsFloat() : 0.5f);
            float rotationY = InputSanitizer.sanitizeRotation(
                entity.has("rotation") ? entity.get("rotation").getAsFloat() : 0f);

            // Build transformation with rotation-aware pivot
            float halfScale = scale * 0.5f;
            float pivotX, pivotY, pivotZ;
            float rotRad;

            // Fast path: no rotation (common case - many patterns don't rotate entities)
            if (rotationY == 0f) {
                rotRad = 0f;
                // Center the block visual on the entity position:
                // Block model occupies (0,0,0)→(1,1,1), scaled by S, so visual center
                // is at T + S/2. Setting T = -S/2 places the visual center at entity pos.
                pivotX = -halfScale;
                pivotY = -halfScale;
                pivotZ = -halfScale;
            } else {
                rotRad = (float) Math.toRadians(rotationY);
                // Lookup cos/sin from per-tick cache, keyed by float bit pattern.
                // Values packed into a single long: upper 32 = cos bits, lower 32 = sin bits.
                int rotBits = Float.floatToRawIntBits(rotRad);
                Long packed = trigCache.get(rotBits);
                float cosR, sinR;
                if (packed != null) {
                    cosR = Float.intBitsToFloat((int)(packed >>> 32));
                    sinR = Float.intBitsToFloat((int)(packed & 0xFFFFFFFFL));
                } else {
                    cosR = (float) Math.cos(rotRad);
                    sinR = (float) Math.sin(rotRad);
                    trigCache.put(rotBits,
                        ((long) Float.floatToRawIntBits(cosR) << 32) | (Float.floatToRawIntBits(sinR) & 0xFFFFFFFFL));
                }
                // Rotation-aware centering pivot: rotate the -halfScale offset
                // by the entity's Y rotation so the block stays centered after rotation.
                pivotX = -halfScale * cosR + halfScale * sinR;
                pivotY = -halfScale;
                pivotZ = -halfScale * sinR - halfScale * cosR;
            }

            // Create transformation using scratch JOML objects (Transformation copies internally).
            scratchTranslation.set(pivotX, pivotY, pivotZ);
            scratchLeftRotation.set(rotRad, 0, 1, 0);
            scratchScale.set(scale, scale, scale);
            Transformation transform = new Transformation(
                scratchTranslation,
                scratchLeftRotation,
                scratchScale,
                IDENTITY_ROTATION
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
            for (int i = 0; i < bandCount; i++) {
                bands[i] = InputSanitizer.sanitizeBandValue(bandsJson.get(i).getAsDouble());
            }
            double amplitude = InputSanitizer.sanitizeAmplitude(
                msg.has("amplitude") ? msg.get("amplitude").getAsDouble() : 0.0);
            long frame = msg.has("frame") ? msg.get("frame").getAsLong() : messagesProcessed.get();

            AudioState audioState = new AudioState(
                bands, amplitude, isBeat, beatIntensity, tempoConfidence, beatPhase, frame);
            plugin.getParticleVisualizationManager().updateAudioState(audioState);

            // Forward audio state to bitmap pattern manager (self-ticks at 20 TPS)
            if (plugin.getBitmapPatternManager() != null) {
                plugin.getBitmapPatternManager().updateAudioState(audioState);
            }

            // Forward audio state to decorator manager
            if (plugin.getDecoratorManager() != null) {
                plugin.getDecoratorManager().updateAudioState(audioState);
            }
        }
    }

    /**
     * Get processing statistics.
     */
    public String getStats() {
        long dropped = messagesDropped.get();
        String stats = "Messages: " + messagesProcessed.get() +
                ", Batches: " + batchesSent.get() +
                ", Queue: " + messageQueue.size();
        if (dropped > 0) {
            stats += ", Dropped: " + dropped;
        }
        return stats;
    }
}
