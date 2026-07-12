package com.audioviz.protocol;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MessageQueueBackpressureTest {

    private static final String RAW_MESSAGE = "{\"type\":\"control\"}";

    @Test
    void saturatedRawParserRejectsWithoutRunningOnCaller() throws Exception {
        MessageHandler messageHandler = mock(MessageHandler.class);
        MessageQueue queue = new MessageQueue(messageHandler);
        CountDownLatch workersEntered = new CountDownLatch(2);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        CountDownLatch releaseOverflowGuard = new CountDownLatch(1);
        AtomicBoolean overflowGuardRan = new AtomicBoolean();
        CompletableFuture<Void> overflowSubmission = null;

        MessageQueue.MessageGuard blockingWorkerGuard = operation -> {
            workersEntered.countDown();
            try {
                if (!releaseWorkers.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release parser worker");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
            operation.run();
            return true;
        };
        MessageQueue.MessageGuard overflowGuard = operation -> {
            overflowGuardRan.set(true);
            try {
                if (!releaseOverflowGuard.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Overflow task ran instead of being rejected");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
            operation.run();
            return true;
        };

        try {
            queue.enqueueRaw(RAW_MESSAGE, blockingWorkerGuard);
            queue.enqueueRaw(RAW_MESSAGE, blockingWorkerGuard);
            assertTrue(workersEntered.await(5, TimeUnit.SECONDS));

            for (int task = 0; task < 64; task++) {
                queue.enqueueRaw(RAW_MESSAGE);
            }

            overflowSubmission = CompletableFuture.runAsync(
                () -> queue.enqueueRaw(RAW_MESSAGE, overflowGuard)
            );
            overflowSubmission.get(1, TimeUnit.SECONDS);

            assertFalse(overflowGuardRan.get(), "Rejected work must never run on the caller");
            assertEquals(1, droppedCount(queue));
        } finally {
            releaseOverflowGuard.countDown();
            releaseWorkers.countDown();
            if (overflowSubmission != null) {
                overflowSubmission.get(5, TimeUnit.SECONDS);
            }
            queue.stop();
        }
    }

    @Test
    void parsedQueueIsBoundedAndDropsOldestMessage() throws Exception {
        MessageHandler messageHandler = mock(MessageHandler.class);
        MessageQueue queue = new MessageQueue(messageHandler);

        try {
            Object parsedQueue = privateField(queue, "queue");
            assertInstanceOf(ArrayBlockingQueue.class, parsedQueue);

            for (int marker = 0; marker <= 1000; marker++) {
                JsonObject message = new JsonObject();
                message.addProperty("type", "control");
                message.addProperty("marker", marker);
                queue.enqueue(message);
            }

            assertTrue(queue.getStats().contains("Queue: 1000"));
            assertTrue(queue.getStats().contains("Dropped: 1"));

            queue.processTick();

            ArgumentCaptor<JsonObject> messages = ArgumentCaptor.forClass(JsonObject.class);
            verify(messageHandler, times(1000)).handleMessage(eq("control"), messages.capture());
            assertEquals(1, messages.getAllValues().getFirst().get("marker").getAsInt());
            assertEquals(1000, messages.getAllValues().getLast().get("marker").getAsInt());
        } finally {
            queue.stop();
        }
    }

    @Test
    void stopCountsDiscardedParserTasksClearsStateAndRejectsNewIntake() throws Exception {
        MessageHandler messageHandler = mock(MessageHandler.class);
        MessageQueue queue = new MessageQueue(messageHandler);
        CountDownLatch workersEntered = new CountDownLatch(2);
        AtomicBoolean interruptRestored = new AtomicBoolean();

        MessageQueue.MessageGuard blockingWorkerGuard = operation -> {
            workersEntered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
            operation.run();
            return true;
        };

        queue.enqueueRaw(RAW_MESSAGE, blockingWorkerGuard);
        queue.enqueueRaw(RAW_MESSAGE, blockingWorkerGuard);
        assertTrue(workersEntered.await(5, TimeUnit.SECONDS));
        for (int task = 0; task < 64; task++) {
            queue.enqueueRaw(RAW_MESSAGE);
        }

        JsonObject parsedMessage = new JsonObject();
        parsedMessage.addProperty("type", "control");
        queue.enqueue(parsedMessage);
        privateMap(queue, "batchCandidatesByZone").put("main", new ArrayDeque<>());
        privateMap(queue, "bitmapCandidatesByZone").put("main", new ArrayDeque<>());

        Thread stopThread = new Thread(() -> {
            queue.stop();
            interruptRestored.set(Thread.currentThread().isInterrupted());
        }, "message-queue-stop-test");
        stopThread.start();
        stopThread.interrupt();
        stopThread.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(stopThread.isAlive(), "stop must await hard parser termination");
        assertTrue(interruptRestored.get(), "stop must restore interrupt status");
        assertTrue(droppedCount(queue) >= 66, "queued and interrupted parser work must be counted");
        assertEquals(0, privateCollectionSize(queue, "queue"));
        assertTrue(privateMap(queue, "batchCandidatesByZone").isEmpty());
        assertTrue(privateMap(queue, "bitmapCandidatesByZone").isEmpty());

        assertDoesNotThrow(() -> queue.enqueue(parsedMessage));
        assertDoesNotThrow(() -> queue.enqueueRaw(RAW_MESSAGE));
        assertEquals(0, privateCollectionSize(queue, "queue"));
        assertDoesNotThrow(queue::processTick);
        verifyNoInteractions(messageHandler);

        queue.stop();
    }

    private static long droppedCount(MessageQueue queue) {
        String stats = queue.getStats();
        int marker = stats.indexOf("Dropped: ");
        if (marker < 0) {
            return 0;
        }
        return Long.parseLong(stats.substring(marker + 9).split(",", 2)[0]);
    }

    private static Object privateField(MessageQueue queue, String fieldName) throws Exception {
        Field field = MessageQueue.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(queue);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> privateMap(MessageQueue queue, String fieldName)
            throws Exception {
        return (Map<Object, Object>) privateField(queue, fieldName);
    }

    private static int privateCollectionSize(MessageQueue queue, String fieldName) throws Exception {
        return ((java.util.Collection<?>) privateField(queue, fieldName)).size();
    }
}
