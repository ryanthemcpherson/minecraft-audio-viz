package com.audioviz.protocol;

import com.audioviz.AudioVizMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance message queue for WebSocket messages.
 * Ported from Paper plugin — removed Bukkit scheduler deps.
 * processTick() is called directly from AudioVizMod.tick().
 *
 * Features:
 * - Async JSON parsing on dedicated thread pool
 * - Tick-based batch processing
 * - Backpressure: drops oldest messages when queue is full
 * - Keeps only latest batch_update/bitmap_frame per zone per tick
 */
public class MessageQueue {

    private final MessageHandler messageHandler;

    // Queue for parsed JSON messages
    private final ConcurrentLinkedQueue<JsonObject> queue = new ConcurrentLinkedQueue<>();

    // Dedicated thread pool for JSON parsing
    private final ExecutorService jsonExecutor;

    // Stats
    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong batchesSent = new AtomicLong(0);
    private final AtomicLong messagesDropped = new AtomicLong(0);

    private static final int MAX_QUEUE_SIZE = 1000;

    // Reusable per-tick maps — cleared each tick
    private final HashMap<String, JsonObject> latestBatchByZone = new HashMap<>(4);
    private final HashMap<String, JsonObject> latestBitmapFrameByZone = new HashMap<>(4);

    public MessageQueue(MessageHandler messageHandler) {
        this.messageHandler = messageHandler;
        this.jsonExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "AudioViz-JSON-Parser");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Enqueue a raw JSON string for async parsing.
     * Called from WebSocket thread — must be thread-safe and non-blocking.
     */
    public void enqueueRaw(String rawJson) {
        jsonExecutor.submit(() -> {
            try {
                JsonObject json = JsonParser.parseString(rawJson).getAsJsonObject();
                if (queue.size() >= MAX_QUEUE_SIZE) {
                    queue.poll();
                    long dropped = messagesDropped.incrementAndGet();
                    if (dropped % 100 == 1) {
                        AudioVizMod.LOGGER.warn("MessageQueue backpressure: dropped (total: {})", dropped);
                    }
                }
                queue.offer(json);
            } catch (Exception e) {
                AudioVizMod.LOGGER.warn("Failed to parse JSON message", e);
            }
        });
    }

    /**
     * Enqueue an already-parsed JSON object.
     */
    public void enqueue(JsonObject json) {
        if (queue.size() >= MAX_QUEUE_SIZE) {
            queue.poll();
            messagesDropped.incrementAndGet();
        }
        queue.offer(json);
    }

    /**
     * Process all queued messages for this tick.
     * Called on the server thread from AudioVizMod.tick().
     */
    public void processTick() {
        latestBatchByZone.clear();
        latestBitmapFrameByZone.clear();

        // Drain queue, keeping only latest batch_update/bitmap_frame per zone
        JsonObject msg;
        while ((msg = queue.poll()) != null) {
            messagesProcessed.incrementAndGet();

            String type = msg.has("type") ? msg.get("type").getAsString() : "unknown";

            if ("batch_update".equals(type)) {
                String zoneName = msg.has("zone") ? msg.get("zone").getAsString() : "main";
                JsonObject replaced = latestBatchByZone.put(zoneName, msg);
                if (replaced != null) messagesDropped.incrementAndGet();
            } else if ("bitmap_frame".equals(type)) {
                String zoneName = msg.has("zone") ? msg.get("zone").getAsString() : "main";
                JsonObject replaced = latestBitmapFrameByZone.put(zoneName, msg);
                if (replaced != null) messagesDropped.incrementAndGet();
            } else {
                try {
                    messageHandler.handleMessage(type, msg);
                } catch (Exception e) {
                    AudioVizMod.LOGGER.warn("Error handling message type: {}", type, e);
                }
            }
        }

        // Process latest batch_update per zone
        for (Map.Entry<String, JsonObject> entry : latestBatchByZone.entrySet()) {
            try {
                messageHandler.handleMessage("batch_update", entry.getValue());
                batchesSent.incrementAndGet();
            } catch (Exception e) {
                AudioVizMod.LOGGER.warn("Error handling batch_update for zone {}", entry.getKey(), e);
            }
        }

        // Process latest bitmap_frame per zone
        for (JsonObject frame : latestBitmapFrameByZone.values()) {
            try {
                messageHandler.handleMessage("bitmap_frame", frame);
                batchesSent.incrementAndGet();
            } catch (Exception e) {
                AudioVizMod.LOGGER.warn("Error handling bitmap_frame", e);
            }
        }
    }

    /**
     * Stop the message processor.
     */
    public void stop() {
        jsonExecutor.shutdown();
        try {
            if (!jsonExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                jsonExecutor.shutdownNow();
                AudioVizMod.LOGGER.warn("MessageQueue JSON executor did not terminate gracefully");
            }
        } catch (InterruptedException e) {
            jsonExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        long dropped = messagesDropped.get();
        AudioVizMod.LOGGER.info("MessageQueue stopped. Processed {} messages in {} batches{}",
            messagesProcessed.get(), batchesSent.get(),
            (dropped > 0 ? " (" + dropped + " dropped)" : ""));
    }

    public String getStats() {
        long dropped = messagesDropped.get();
        String stats = "Messages: " + messagesProcessed.get() +
                ", Batches: " + batchesSent.get() +
                ", Queue: " + queue.size();
        if (dropped > 0) {
            stats += ", Dropped: " + dropped;
        }
        return stats;
    }
}
