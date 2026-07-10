package com.audioviz.protocol;

import com.audioviz.AudioVizMod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayDeque;
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

    private final MessageHandler messageHandler;

    // Queue for parsed JSON messages
    private final ConcurrentLinkedQueue<QueuedMessage> queue = new ConcurrentLinkedQueue<>();

    // Dedicated thread pool for JSON parsing
    private final ExecutorService jsonExecutor;

    // Stats
    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong batchesSent = new AtomicLong(0);
    private final AtomicLong messagesDropped = new AtomicLong(0);
    private final AtomicLong messagesErrored = new AtomicLong(0);

    private static final int MAX_QUEUE_SIZE = 1000;

    // Reusable per-tick maps — cleared each tick
    private final HashMap<String, ArrayDeque<QueuedMessage>> batchCandidatesByZone =
        new HashMap<>(4);
    private final HashMap<String, ArrayDeque<QueuedMessage>> bitmapCandidatesByZone =
        new HashMap<>(4);

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
        enqueueRaw(rawJson, ALLOW_ALL);
    }

    /**
     * Enqueue raw JSON with a guard that remains attached through parsing and execution.
     */
    public void enqueueRaw(String rawJson, MessageGuard guard) {
        jsonExecutor.submit(() -> {
            try {
                if (!guard.runIfValid(NO_OP)) {
                    messagesDropped.incrementAndGet();
                    return;
                }
                JsonObject json = JsonParser.parseString(rawJson).getAsJsonObject();
                offer(new QueuedMessage(json, guard));
            } catch (Exception e) {
                AudioVizMod.LOGGER.warn("Failed to parse JSON message", e);
            }
        });
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
        offer(new QueuedMessage(json, guard));
    }

    private void offer(QueuedMessage message) {
        if (queue.size() >= MAX_QUEUE_SIZE) {
            queue.poll();
            long dropped = messagesDropped.incrementAndGet();
            if (dropped % 100 == 1) {
                AudioVizMod.LOGGER.warn(
                    "MessageQueue backpressure: dropped (total: {})",
                    dropped
                );
            }
        }
        queue.offer(message);
    }

    /**
     * Process all queued messages for this tick.
     * Called on the server thread from AudioVizMod.tick().
     */
    public void processTick() {
        batchCandidatesByZone.clear();
        bitmapCandidatesByZone.clear();

        // Keep fallback candidates so a stale newest message cannot suppress
        // an older valid update for the same zone.
        QueuedMessage queuedMessage;
        while ((queuedMessage = queue.poll()) != null) {
            messagesProcessed.incrementAndGet();

            QueuedMessage current = queuedMessage;
            try {
                boolean accepted = current.guard().runIfValid(
                    () -> classifyAndHandle(current)
                );
                if (!accepted) {
                    messagesDropped.incrementAndGet();
                }
            } catch (Exception exception) {
                messagesDropped.incrementAndGet();
                messagesErrored.incrementAndGet();
                AudioVizMod.LOGGER.warn(
                    "Dropped queued message after processing error ({})",
                    exception.getClass().getSimpleName()
                );
            }
        }

        processCoalesced("batch_update", batchCandidatesByZone);
        processCoalesced("bitmap_frame", bitmapCandidatesByZone);
    }

    private void classifyAndHandle(QueuedMessage queuedMessage) {
        JsonObject message = queuedMessage.message();
        String type = message.has("type")
            ? message.get("type").getAsString()
            : "unknown";

        if ("batch_update".equals(type)) {
            String zoneName = message.has("zone")
                ? message.get("zone").getAsString()
                : "main";
            batchCandidatesByZone
                .computeIfAbsent(zoneName, ignored -> new ArrayDeque<>())
                .addLast(queuedMessage);
        } else if ("bitmap_frame".equals(type)) {
            String zoneName = message.has("zone")
                ? message.get("zone").getAsString()
                : "main";
            bitmapCandidatesByZone
                .computeIfAbsent(zoneName, ignored -> new ArrayDeque<>())
                .addLast(queuedMessage);
        } else {
            messageHandler.handleMessage(type, message);
        }
    }

    private void processCoalesced(
        String type,
        Map<String, ArrayDeque<QueuedMessage>> candidatesByZone
    ) {
        for (Map.Entry<String, ArrayDeque<QueuedMessage>> entry : candidatesByZone.entrySet()) {
            ArrayDeque<QueuedMessage> candidates = entry.getValue();
            int candidateCount = candidates.size();
            boolean selected = false;

            QueuedMessage candidate;
            while ((candidate = candidates.pollLast()) != null) {
                QueuedMessage current = candidate;
                try {
                    boolean executed = current.guard().runIfValid(
                        () -> messageHandler.handleMessage(type, current.message())
                    );
                    if (executed) {
                        batchesSent.incrementAndGet();
                        selected = true;
                        break;
                    }
                } catch (Exception exception) {
                    AudioVizMod.LOGGER.warn(
                        "Dropped coalesced {} message after processing error ({})",
                        type,
                        exception.getClass().getSimpleName()
                    );
                    messagesErrored.incrementAndGet();
                    break;
                }
            }

            messagesDropped.addAndGet(selected ? candidateCount - 1L : candidateCount);
        }
    }

    private record QueuedMessage(JsonObject message, MessageGuard guard) { }

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
        long errors = messagesErrored.get();
        if (errors > 0) {
            stats += ", Errors: " + errors;
        }
        return stats;
    }
}
