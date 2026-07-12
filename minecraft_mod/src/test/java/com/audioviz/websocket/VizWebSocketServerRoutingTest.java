package com.audioviz.websocket;

import com.audioviz.connection.ConnectionStateListener;
import com.audioviz.protocol.MessageHandler;
import com.audioviz.protocol.MessageQueue;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VizWebSocketServerRoutingTest {

    private MessageHandler messageHandler;
    private ConnectionStateListener connectionStateListener;
    private WebSocket connection;
    private ClientHandshake handshake;

    @BeforeEach
    void setUp() {
        messageHandler = mock(MessageHandler.class);
        connectionStateListener = mock(ConnectionStateListener.class);
        connection = mock(WebSocket.class);
        handshake = mock(ClientHandshake.class);
        lenient().when(connection.getRemoteSocketAddress())
            .thenReturn(new InetSocketAddress("127.0.0.1", 54321));
        lenient().when(connection.isOpen()).thenReturn(true);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("highFrequencyMessages")
    void exactHighFrequencyTypeAlwaysUsesBoundedQueue(
        String caseName,
        String type,
        String rawMessage
    ) {
        MessageQueue queue = new MessageQueue(messageHandler);
        RecordingExecutor serverExecutor = new RecordingExecutor();
        VizWebSocketServer server = newServer(queue, serverExecutor);
        try {
            openActiveClient(server);

            server.onMessage(connection, rawMessage);

            verifyNoInteractions(messageHandler);
            assertEquals(0, serverExecutor.submissionCount());
            awaitQueueSize(queue, 1);
            queue.processTick();

            verify(messageHandler).handleMessage(eq(type), any(JsonObject.class));
            assertEquals(0, serverExecutor.submissionCount());
        } finally {
            queue.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("pongMessages")
    void canonicalAndTypeLastPongOnlyRefreshHeartbeat(String rawMessage) throws Exception {
        MessageQueue queue = new MessageQueue(messageHandler);
        RecordingExecutor serverExecutor = new RecordingExecutor();
        VizWebSocketServer server = newServer(queue, serverExecutor);
        try {
            openActiveClient(server);
            ConcurrentHashMap<WebSocket, Long> pongTimes = privateField(
                server,
                "lastPongTime"
            );
            pongTimes.put(connection, 1L);

            server.onMessage(connection, rawMessage);

            verifyNoInteractions(messageHandler);
            awaitCondition(() -> pongTimes.get(connection) > 1L, "pong heartbeat update");
            verify(connection, never()).send(anyString());
            assertEquals(0, serverExecutor.submissionCount());
            assertEquals(0, queueSize(queue));
        } finally {
            queue.stop();
        }
    }

    @Test
    void nestedPongCannotSpoofTopLevelControlRouting() throws Exception {
        MessageQueue queue = new MessageQueue(messageHandler);
        RecordingExecutor serverExecutor = new RecordingExecutor();
        VizWebSocketServer server = newServer(queue, serverExecutor);
        CountDownLatch handlerCalled = new CountDownLatch(1);
        JsonObject response = new JsonObject();
        response.addProperty("type", "status");
        when(messageHandler.handleMessage(eq("get_status"), any(JsonObject.class)))
            .thenAnswer(invocation -> {
                handlerCalled.countDown();
                return response;
            });
        List<String> sent = new CopyOnWriteArrayList<>();
        try {
            openActiveClient(server);
            doAnswer(invocation -> {
                sent.add(invocation.getArgument(0));
                return null;
            }).when(connection).send(anyString());

            server.onMessage(
                connection,
                "{\"meta\":{\"type\":\"pong\"},\"type\":\"get_status\",\"_seq\":17}"
            );

            assertTrue(handlerCalled.await(2, TimeUnit.SECONDS));
            awaitCondition(() -> !sent.isEmpty(), "control response");
            JsonObject actual = JsonParser.parseString(sent.get(0)).getAsJsonObject();
            assertEquals("status", actual.get("type").getAsString());
            assertEquals(17, actual.get("_seq").getAsInt());
            assertEquals(1, serverExecutor.submissionCount());
        } finally {
            queue.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("highFrequencyTypes")
    void highFrequencyTypeRemainsBoundedWhenLegacyAsyncToggleIsOff(String type) {
        MessageQueue queue = new MessageQueue(messageHandler);
        RecordingExecutor serverExecutor = new RecordingExecutor();
        VizWebSocketServer server = newServer(queue, serverExecutor);
        try {
            openActiveClient(server);
            server.setAsyncEnabled(false);

            server.onMessage(connection, typeLastMessage(type));

            verifyNoInteractions(messageHandler);
            assertEquals(0, serverExecutor.submissionCount());
            awaitQueueSize(queue, 1);
            queue.processTick();
            verify(messageHandler).handleMessage(eq(type), any(JsonObject.class));
        } finally {
            queue.stop();
        }
    }

    @Test
    void paddedHighFrequencyFloodCannotBypassParserAdmissionBound() throws Exception {
        MessageQueue queue = new MessageQueue(messageHandler);
        RecordingExecutor serverExecutor = new RecordingExecutor();
        VizWebSocketServer server = newServer(queue, serverExecutor);
        CountDownLatch parserWorkersEntered = new CountDownLatch(2);
        CountDownLatch releaseParserWorkers = new CountDownLatch(1);
        MessageQueue.MessageGuard blockingGuard = operation -> {
            parserWorkersEntered.countDown();
            try {
                if (!releaseParserWorkers.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release parser workers");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Parser worker interrupted", exception);
            }
            operation.run();
            return true;
        };

        try {
            openActiveClient(server);
            queue.enqueueRaw("{\"type\":\"audio\"}", blockingGuard);
            queue.enqueueRaw("{\"type\":\"audio\"}", blockingGuard);
            assertTrue(parserWorkersEntered.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 64; index++) {
                queue.enqueueRaw("{\"type\":\"audio\",\"index\":" + index + "}");
            }

            String paddedFrame = typeLastMessage("audio");
            for (int index = 0; index < 10; index++) {
                server.onMessage(connection, paddedFrame);
            }

            verifyNoInteractions(messageHandler);
            assertEquals(0, serverExecutor.submissionCount());
            awaitCondition(() -> droppedCount(queue) == 10L, "ten rejected parser admissions");
            assertEquals(0, queueSize(queue));
        } finally {
            releaseParserWorkers.countDown();
            queue.stop();
        }
    }

    private VizWebSocketServer newServer(MessageQueue queue, Executor serverExecutor) {
        return new VizWebSocketServer(
            "127.0.0.1",
            0,
            "",
            messageHandler,
            queue,
            serverExecutor,
            connectionStateListener
        );
    }

    private void openActiveClient(VizWebSocketServer server) {
        server.onOpen(connection, handshake);
        clearInvocations(connection);
    }

    private static Stream<Arguments> highFrequencyMessages() {
        return highFrequencyTypes()
            .flatMap(type -> Stream.of(
                Arguments.of(type + " type-last", type, typeLastMessage(type)),
                Arguments.of(type + " legal whitespace", type, whitespaceMessage(type))
            ));
    }

    private static Stream<String> highFrequencyTypes() {
        return Stream.of(
            "batch_update",
            "audio",
            "dj_audio_frame",
            "audio_frame",
            "bitmap_frame",
            "audio_state",
            "voice_audio"
        );
    }

    private static Stream<String> pongMessages() {
        return Stream.of(
            "{\"type\":\"pong\"}",
            "{\"padding\":\"" + "x".repeat(80) + "\",\"type\":\"pong\"}"
        );
    }

    private static String typeLastMessage(String type) {
        return "{\"zone\":\"main\",\"padding\":\"" + "x".repeat(80)
            + "\",\"escaped\":\"\\\"type\\\":\\\"pong\\\"\",\"type\":\"" + type + "\"}";
    }

    private static String whitespaceMessage(String type) {
        return "{\n  \"type\" \t:\r\n \"" + type
            + "\",\n  \"zone\" : \"main\"\n}";
    }

    private static void awaitQueueSize(MessageQueue queue, int expectedSize) {
        awaitCondition(() -> queueSize(queue) == expectedSize, "queue size " + expectedSize);
    }

    private static int queueSize(MessageQueue queue) {
        String stats = queue.getStats();
        String marker = "Queue: ";
        return Integer.parseInt(stats.substring(stats.indexOf(marker) + marker.length())
            .split(",", 2)[0]);
    }

    private static long droppedCount(MessageQueue queue) {
        String stats = queue.getStats();
        String marker = "Dropped: ";
        int markerIndex = stats.indexOf(marker);
        if (markerIndex < 0) {
            return 0L;
        }
        return Long.parseLong(stats.substring(markerIndex + marker.length()).split(",", 2)[0]);
    }

    private static void awaitCondition(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for " + description);
            }
            Thread.onSpinWait();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T privateField(Object target, String fieldName) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static final class RecordingExecutor implements Executor {
        private final AtomicInteger submissions = new AtomicInteger();

        @Override
        public void execute(Runnable command) {
            submissions.incrementAndGet();
            command.run();
        }

        private int submissionCount() {
            return submissions.get();
        }
    }
}
