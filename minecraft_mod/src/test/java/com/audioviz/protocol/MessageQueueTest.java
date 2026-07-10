package com.audioviz.protocol;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class MessageQueueTest {

    private MessageHandler messageHandler;
    private MessageQueue messageQueue;

    @BeforeEach
    void setUp() {
        messageHandler = mock(MessageHandler.class);
        messageQueue = new MessageQueue(messageHandler);
    }

    @AfterEach
    void tearDown() {
        messageQueue.stop();
    }

    @Test
    void guardInvalidatedAfterRawParsePreventsExecution() {
        AtomicBoolean valid = new AtomicBoolean(true);
        MessageQueue.MessageGuard guard = operation -> {
            if (!valid.get()) {
                return false;
            }
            operation.run();
            return true;
        };
        JsonObject message = batchUpdate("main", "parsed-before-close");

        messageQueue.enqueueRaw(message.toString(), guard);
        awaitQueueSize(messageQueue, 1);
        valid.set(false);

        messageQueue.processTick();

        verifyNoInteractions(messageHandler);
    }

    @Test
    void staleLatestUpdateDoesNotSuppressOlderValidSameZoneUpdate() {
        MessageQueue.MessageGuard validGuard = operation -> {
            operation.run();
            return true;
        };
        MessageQueue.MessageGuard staleGuard = operation -> false;
        JsonObject olderValid = batchUpdate("main", "older-valid");
        JsonObject latestStale = batchUpdate("main", "latest-stale");

        messageQueue.enqueue(olderValid, validGuard);
        messageQueue.enqueue(latestStale, staleGuard);

        messageQueue.processTick();

        verify(messageHandler).handleMessage(eq("batch_update"), same(olderValid));
        verifyNoMoreInteractions(messageHandler);
    }

    private static JsonObject batchUpdate(String zone, String marker) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "batch_update");
        message.addProperty("zone", zone);
        message.addProperty("marker", marker);
        return message;
    }

    private static void awaitQueueSize(MessageQueue queue, int expectedSize) {
        String expected = "Queue: " + expectedSize;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!queue.getStats().contains(expected)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for " + expected);
            }
            Thread.onSpinWait();
        }
    }
}
