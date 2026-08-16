package com.audioviz.protocol;

import com.audioviz.AudioVizPlugin;
import com.audioviz.entities.EntityPoolManager;
import com.audioviz.entities.EntityUpdate;
import com.audioviz.latency.LatencyTracker;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MessageQueueBackpressureTest {

    private static final String RAW_MESSAGE = "{\"type\":\"control\"}";

    @Test
    void saturatedRawParserRejectsWithoutRunningOnCaller() throws Exception {
        QueueFixture fixture = newFixture();
        MessageQueue queue = fixture.queue();
        CountDownLatch workersEntered = new CountDownLatch(2);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        CountDownLatch releaseOverflowGuard = new CountDownLatch(1);
        AtomicBoolean overflowGuardRan = new AtomicBoolean();
        AtomicReference<RuntimeException> overflowFailure = new AtomicReference<>();
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
                () -> queue.parseAndDispatch(
                    RAW_MESSAGE,
                    overflowGuard,
                    (json, guard) -> queue.enqueue(json, guard),
                    overflowFailure::set
                )
            );
            overflowSubmission.get(1, TimeUnit.SECONDS);

            assertFalse(overflowGuardRan.get(), "Rejected work must never run on the caller");
            RejectedExecutionException rejection = assertInstanceOf(
                RejectedExecutionException.class,
                overflowFailure.get()
            );
            assertEquals("WebSocket parser queue is full", rejection.getMessage());
            assertEquals(1, droppedCount(queue));
            assertEquals(64, queue.getMetrics().rawQueueDepth());
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
        QueueFixture fixture = newFixture();
        MessageQueue queue = fixture.queue();

        try {
            Object parsedQueue = privateField(queue, "messageQueue");
            assertInstanceOf(ArrayBlockingQueue.class, parsedQueue);

            for (int marker = 0; marker <= 1000; marker++) {
                JsonObject message = new JsonObject();
                message.addProperty("type", "control");
                message.addProperty("marker", marker);
                queue.enqueue(message);
            }

            assertTrue(queue.getStats().contains("Queue: 1000"));
            assertTrue(queue.getStats().contains("Dropped: 1"));
            assertEquals(
                new MessageQueue.QueueMetrics(0, 0, 1, 1000, 0),
                queue.getMetrics()
            );

            invokeProcessTick(queue);

            ArgumentCaptor<JsonObject> messages = ArgumentCaptor.forClass(JsonObject.class);
            verify(fixture.messageHandler(), org.mockito.Mockito.times(1000))
                .handleMessage(eq("control"), messages.capture());
            assertEquals(1, messages.getAllValues().getFirst().get("marker").getAsInt());
            assertEquals(1000, messages.getAllValues().getLast().get("marker").getAsInt());
            assertEquals(
                new MessageQueue.QueueMetrics(1000, 0, 1, 0, 0),
                queue.getMetrics()
            );
        } finally {
            queue.stop();
        }
    }

    @Test
    void processTickRecordsMainThreadUpdateDuration() {
        QueueFixture fixture = newFixture();

        try {
            invokeProcessTick(fixture.queue());

            ArgumentCaptor<Double> duration = ArgumentCaptor.forClass(Double.class);
            verify(fixture.latencyTracker()).recordMainThreadUpdateDuration(duration.capture());
            assertTrue(duration.getValue() >= 0.0);
        } finally {
            fixture.queue().stop();
        }
    }

    @Test
    void stopCountsDiscardedParserTasksClearsStateAndRejectsNewIntake() throws Exception {
        QueueFixture fixture = newFixture();
        MessageQueue queue = fixture.queue();
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

        privateQueue(queue, "entityUpdateQueue").offer(mock(EntityUpdate.class));
        privateMap(queue, "batchCandidatesByZone").put("main", new ArrayDeque<>());
        privateMap(queue, "bitmapCandidatesByZone").put("main", new ArrayDeque<>());
        privateMap(queue, "updatesByZone").put(
            "main",
            new ArrayList<>(List.of(mock(EntityUpdate.class)))
        );
        privateMap(queue, "trigCache").put(1, 1L);
        privateMap(queue, "lastBeatTimestampByZone").put("main", 1L);

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
        assertEquals(0, privateCollectionSize(queue, "messageQueue"));
        assertEquals(0, privateCollectionSize(queue, "entityUpdateQueue"));
        assertTrue(privateMap(queue, "batchCandidatesByZone").isEmpty());
        assertTrue(privateMap(queue, "bitmapCandidatesByZone").isEmpty());
        assertTrue(privateMap(queue, "updatesByZone").isEmpty());
        assertTrue(privateMap(queue, "trigCache").isEmpty());
        assertTrue(privateMap(queue, "lastBeatTimestampByZone").isEmpty());

        assertDoesNotThrow(() -> queue.enqueue(parsedMessage));
        assertDoesNotThrow(() -> queue.enqueueRaw(RAW_MESSAGE));
        assertEquals(0, privateCollectionSize(queue, "messageQueue"));
        assertDoesNotThrow(() -> invokeProcessTick(queue));
        verifyNoInteractions(fixture.messageHandler(), fixture.entityPoolManager());

        queue.stop();
    }

    private static QueueFixture newFixture() {
        AudioVizPlugin plugin = mock(AudioVizPlugin.class);
        MessageHandler messageHandler = mock(MessageHandler.class);
        EntityPoolManager entityPoolManager = mock(EntityPoolManager.class);
        LatencyTracker latencyTracker = mock(LatencyTracker.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger(MessageQueueBackpressureTest.class.getName()));
        when(plugin.getEntityPoolManager()).thenReturn(entityPoolManager);
        when(plugin.getLatencyTracker()).thenReturn(latencyTracker);
        return new QueueFixture(
            new MessageQueue(plugin, messageHandler),
            messageHandler,
            entityPoolManager,
            latencyTracker
        );
    }

    private static long droppedCount(MessageQueue queue) {
        String stats = queue.getStats();
        int marker = stats.indexOf("Dropped: ");
        if (marker < 0) {
            return 0;
        }
        return Long.parseLong(stats.substring(marker + 9).split(",", 2)[0]);
    }

    private static void invokeProcessTick(MessageQueue queue) {
        try {
            Method processTick = MessageQueue.class.getDeclaredMethod("processTick");
            processTick.setAccessible(true);
            processTick.invoke(queue);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static Object privateField(MessageQueue queue, String fieldName) throws Exception {
        Field field = MessageQueue.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(queue);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Queue<Object> privateQueue(MessageQueue queue, String fieldName)
            throws Exception {
        return (java.util.Queue<Object>) privateField(queue, fieldName);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> privateMap(MessageQueue queue, String fieldName)
            throws Exception {
        return (Map<Object, Object>) privateField(queue, fieldName);
    }

    private static int privateCollectionSize(MessageQueue queue, String fieldName) throws Exception {
        return ((java.util.Collection<?>) privateField(queue, fieldName)).size();
    }

    private record QueueFixture(
        MessageQueue queue,
        MessageHandler messageHandler,
        EntityPoolManager entityPoolManager,
        LatencyTracker latencyTracker
    ) { }
}
