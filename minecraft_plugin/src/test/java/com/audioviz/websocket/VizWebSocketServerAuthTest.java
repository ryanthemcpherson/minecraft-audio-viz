package com.audioviz.websocket;

import com.audioviz.AudioVizPlugin;
import com.audioviz.connection.ConnectionStateListener;
import com.audioviz.entities.EntityPoolManager;
import com.audioviz.entities.EntityUpdate;
import com.audioviz.latency.LatencyTracker;
import com.audioviz.particles.ParticleVisualizationManager;
import com.audioviz.protocol.MessageHandler;
import com.audioviz.protocol.MessageQueue;
import com.audioviz.zones.VisualizationZone;
import com.audioviz.zones.ZoneManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VizWebSocketServerAuthTest {

    private static final String BATCH_UPDATE = "{\"type\":\"batch_update\",\"updates\":[]}";
    private static final String BATCH_WITH_ENTITY = "{\"type\":\"batch_update\","
        + "\"zone\":\"main\",\"entities\":[{\"id\":\"block_0\"}]}";
    private static final String VOICE_AUDIO = "{\"type\":\"voice_audio\",\"data\":\"\"}";
    private static final String AUTH_MESSAGE = "{\"type\":\"auth\",\"token\":\"secret\"}";
    private static final String AUTH_OK = "{\"type\":\"auth_ok\"}";

    @Mock
    private AudioVizPlugin plugin;
    @Mock
    private FileConfiguration config;
    @Mock
    private PluginDescriptionFile description;
    @Mock
    private Server bukkitServer;
    @Mock
    private BukkitScheduler scheduler;
    @Mock
    private MessageHandler messageHandler;
    @Mock
    private MessageQueue messageQueue;
    @Mock
    private ConnectionStateListener connectionStateListener;
    @Mock
    private WebSocket connection;
    @Mock
    private ClientHandshake handshake;

    private List<Runnable> authTimeoutTasks;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getLogger(getClass().getName());
        logger.setUseParentHandlers(false);

        lenient().when(plugin.getConfig()).thenReturn(config);
        lenient().when(config.getString("websocket.address", "127.0.0.1"))
            .thenReturn("127.0.0.1");
        lenient().when(plugin.getLogger()).thenReturn(logger);
        lenient().when(plugin.getDescription()).thenReturn(description);
        lenient().when(description.getVersion()).thenReturn("test");
        lenient().when(plugin.getConnectionStateListener()).thenReturn(connectionStateListener);
        lenient().when(messageQueue.getMetrics()).thenReturn(
            new MessageQueue.QueueMetrics(0, 0, 0, 0, 0)
        );
        lenient().when(connection.getRemoteSocketAddress())
            .thenReturn(new InetSocketAddress("127.0.0.1", 54321));
        lenient().when(connection.isOpen()).thenReturn(true);
        lenient().doAnswer(invocation -> {
            String rawJson = invocation.getArgument(0);
            MessageQueue.MessageGuard guard = invocation.getArgument(1);
            MessageQueue.ParsedMessageAdmission admission = invocation.getArgument(2);
            MessageQueue.ParseFailureHandler failureHandler = invocation.getArgument(3);
            try {
                admission.admit(JsonParser.parseString(rawJson).getAsJsonObject(), guard);
            } catch (RuntimeException exception) {
                failureHandler.onFailure(exception);
            }
            return null;
        }).when(messageQueue).parseAndDispatch(
            anyString(),
            any(MessageQueue.MessageGuard.class),
            any(MessageQueue.ParsedMessageAdmission.class),
            any(MessageQueue.ParseFailureHandler.class)
        );
        authTimeoutTasks = new ArrayList<>();
    }

    @Test
    void secretlessLoopbackNativeClientIsAdmittedImmediately() {
        VizWebSocketServer server = newServer("");

        server.onOpen(connection, handshake);

        JsonObject welcome = sentMessage("connected");
        assertFalse(welcome.get("auth_required").getAsBoolean());
        assertEquals(1, server.getConnectionCount());
        assertTrue(authTimeoutTasks.isEmpty());
        verify(connectionStateListener).onDjConnect(connection.getRemoteSocketAddress().toString());

        server.onMessage(connection, BATCH_UPDATE);
        verify(messageQueue).parseAndDispatch(
            eq(BATCH_UPDATE),
            any(MessageQueue.MessageGuard.class),
            any(MessageQueue.ParsedMessageAdmission.class),
            any(MessageQueue.ParseFailureHandler.class)
        );

        server.onClose(connection, 1000, "closed", true);
        verify(connectionStateListener).onDjDisconnect("remote close (code 1000)");
    }

    @Test
    void parserSaturationReturnsStableServerBusyResponse() {
        doAnswer(invocation -> {
            MessageQueue.ParseFailureHandler failureHandler = invocation.getArgument(3);
            failureHandler.onFailure(new RejectedExecutionException("internal detail"));
            return null;
        }).when(messageQueue).parseAndDispatch(
            anyString(),
            any(MessageQueue.MessageGuard.class),
            any(MessageQueue.ParsedMessageAdmission.class),
            any(MessageQueue.ParseFailureHandler.class)
        );
        VizWebSocketServer server = newServer("");
        server.onOpen(connection, handshake);
        clearInvocations(connection);

        server.onMessage(connection, "{\"type\":\"get_status\"}");

        verify(connection).send(
            "{\"type\":\"error\",\"code\":\"server_busy\","
                + "\"message\":\"Server is busy; retry control messages\"}"
        );
        verify(connection, never()).send(argThat(
            (String message) -> message.contains("internal detail")
        ));
    }

    @Test
    void handlerFailureReturnsSanitizedInvalidMessageResponse() {
        doAnswer(invocation -> {
            MessageQueue.ParseFailureHandler failureHandler = invocation.getArgument(3);
            failureHandler.onFailure(new IllegalArgumentException("sensitive internal detail"));
            return null;
        }).when(messageQueue).parseAndDispatch(
            anyString(),
            any(MessageQueue.MessageGuard.class),
            any(MessageQueue.ParsedMessageAdmission.class),
            any(MessageQueue.ParseFailureHandler.class)
        );
        VizWebSocketServer server = newServer("");
        server.onOpen(connection, handshake);
        clearInvocations(connection);

        server.onMessage(connection, "{\"type\":\"get_status\"}");

        verify(connection).send(
            "{\"type\":\"error\",\"code\":\"invalid_message\","
                + "\"message\":\"Message could not be processed\"}"
        );
        verify(connection, never()).send(
            argThat((String message) -> message.contains("sensitive internal detail"))
        );
    }

    @Test
    void websocketMetricsIncludeQueueAndMainThreadTiming() {
        when(messageQueue.getMetrics()).thenReturn(
            new MessageQueue.QueueMetrics(11, 12, 13, 14, 15)
        );
        LatencyTracker latencyTracker = org.mockito.Mockito.mock(LatencyTracker.class);
        LatencyTracker.RollingWindow updateStats =
            org.mockito.Mockito.mock(LatencyTracker.RollingWindow.class);
        when(plugin.getLatencyTracker()).thenReturn(latencyTracker);
        when(latencyTracker.getMainThreadUpdateStats()).thenReturn(updateStats);
        when(updateStats.getAvg()).thenReturn(1.25);
        when(updateStats.getP95()).thenReturn(2.5);
        when(updateStats.getMax()).thenReturn(4.0);

        JsonObject metrics = newServer("").getMetrics();

        assertEquals(11, metrics.get("queueProcessed").getAsLong());
        assertEquals(12, metrics.get("queueBatches").getAsLong());
        assertEquals(13, metrics.get("queueDropped").getAsLong());
        assertEquals(14, metrics.get("parsedQueueDepth").getAsInt());
        assertEquals(15, metrics.get("rawQueueDepth").getAsInt());
        assertEquals(1.25, metrics.get("mainThreadUpdateAvgMs").getAsDouble());
        assertEquals(2.5, metrics.get("mainThreadUpdateP95Ms").getAsDouble());
        assertEquals(4.0, metrics.get("mainThreadUpdateMaxMs").getAsDouble());
    }

    @Test
    void authenticatedConfigurationWelcomesClientAsPending() {
        VizWebSocketServer server = newServer("secret");

        server.onOpen(connection, handshake);

        JsonObject welcome = sentMessage("connected");
        assertTrue(welcome.get("auth_required").getAsBoolean());
        assertEquals(0, server.getConnectionCount());
        assertEquals(1, authTimeoutTasks.size());
        verify(connectionStateListener, never()).onDjConnect(anyString());

        clearInvocations(connection);
        JsonObject broadcast = new JsonObject();
        broadcast.addProperty("type", "server_notice");
        assertEquals(0, server.broadcast(broadcast));
        verify(connection, never()).send(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "   ",
        "not-json",
        "{}",
        "{\"type\":\"auth\"}",
        "{\"type\":\"auth\",\"token\":\"\"}",
        "{\"type\":\"auth\",\"token\":\" secret\"}",
        "{\"type\":\"auth\",\"token\":\"wrong\"}",
        BATCH_UPDATE
    })
    void rejectsEveryPreAuthMessageExceptExactToken(String message) {
        VizWebSocketServer server = newServer("secret");
        server.onOpen(connection, handshake);

        server.onMessage(connection, message);

        verify(connection).close(4001, "Authentication failed");
        verifyNoInteractions(messageHandler);
        verify(messageQueue, never()).parseAndDispatch(
            anyString(),
            any(MessageQueue.MessageGuard.class),
            any(MessageQueue.ParsedMessageAdmission.class),
            any(MessageQueue.ParseFailureHandler.class)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"type\":\"auth\",\"token\":\"secret\",\"extra\":true}",
        "{\"type\":\"auth\",\"v\":1,\"token\":\"secret\"}"
    })
    void rejectsAuthenticationMessagesOutsideClosedSchema(String message) {
        VizWebSocketServer server = newServer("secret");
        server.onOpen(connection, handshake);
        clearInvocations(connection);

        server.onMessage(connection, message);

        verify(connection).close(4001, "Authentication failed");
        verify(connection, never()).send(AUTH_OK);
        assertEquals(0, server.getConnectionCount());
    }

    @Test
    void rejectsNonStringTokenEvenWhenJsonCoercionWouldMatch() {
        VizWebSocketServer server = newServer("123");
        server.onOpen(connection, handshake);
        clearInvocations(connection);

        server.onMessage(connection, "{\"type\":\"auth\",\"token\":123}");

        verify(connection).close(4001, "Authentication failed");
        verify(connection, never()).send(AUTH_OK);
    }

    @Test
    void rejectsTokenLongerThanSchemaMaximumEvenWhenSecretMatches() {
        String overlongToken = "x".repeat(1025);
        VizWebSocketServer server = newServer(overlongToken);
        server.onOpen(connection, handshake);
        clearInvocations(connection);

        server.onMessage(connection,
            "{\"type\":\"auth\",\"token\":\"" + overlongToken + "\"}");

        verify(connection).close(4001, "Authentication failed");
        verify(connection, never()).send(AUTH_OK);
    }

    @Test
    void acceptsOptionalStringProtocolVersion() {
        VizWebSocketServer server = newServer("secret");
        server.onOpen(connection, handshake);
        clearInvocations(connection);

        server.onMessage(connection,
            "{\"type\":\"auth\",\"v\":\"1.0.0\",\"token\":\"secret\"}");

        verify(connection).send(AUTH_OK);
        assertEquals(1, server.getConnectionCount());
    }

    @Test
    void peerCloseReasonIsNeitherLoggedNorForwarded() {
        VizWebSocketServer server = newServer("");
        Logger logger = plugin.getLogger();
        List<String> logMessages = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logMessages.add(record.getMessage());
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        logger.addHandler(handler);
        server.onOpen(connection, handshake);

        try {
            server.onClose(connection, 4009, "SECRET_SENTINEL\r\nforged-log", true);
        } finally {
            logger.removeHandler(handler);
        }

        verify(connectionStateListener).onDjDisconnect("remote close (code 4009)");
        assertTrue(logMessages.stream().noneMatch(message ->
            message.contains("SECRET_SENTINEL") || message.contains("\r") || message.contains("\n")));
    }

    @Test
    void localCloseUsesOnlyDirectionAndNumericCode() {
        VizWebSocketServer server = newServer("");
        server.onOpen(connection, handshake);

        server.onClose(connection, 1001, "SECRET_SENTINEL\r\nforged-log", false);

        verify(connectionStateListener).onDjDisconnect("local close (code 1001)");
    }

    @Test
    void exactTokenSendsAuthOkThenPermitsMessages() {
        VizWebSocketServer server = newServer("secret");
        server.onOpen(connection, handshake);
        clearInvocations(connection);

        server.onMessage(connection, "{\"type\":\"auth\",\"token\":\"secret\"}");

        InOrder admission = inOrder(connection, connectionStateListener);
        admission.verify(connection).send("{\"type\":\"auth_ok\"}");
        admission.verify(connectionStateListener)
            .onDjConnect(connection.getRemoteSocketAddress().toString());
        assertEquals(1, server.getConnectionCount());

        server.onMessage(connection, BATCH_UPDATE);
        verify(messageQueue).parseAndDispatch(
            eq(BATCH_UPDATE),
            any(MessageQueue.MessageGuard.class),
            any(MessageQueue.ParsedMessageAdmission.class),
            any(MessageQueue.ParseFailureHandler.class)
        );
    }

    @Test
    void remainsInactiveWhileAuthOkSendIsBlocked() throws Exception {
        VizWebSocketServer server = newServer("secret");
        server.onOpen(connection, handshake);
        Runnable heartbeat = startAndCaptureHeartbeat(server);
        CountDownLatch ackSendStarted = new CountDownLatch(1);
        CountDownLatch releaseAckSend = new CountDownLatch(1);
        doAnswer(invocation -> {
            ackSendStarted.countDown();
            if (!releaseAckSend.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release auth_ok send");
            }
            return null;
        }).when(connection).send(AUTH_OK);

        CompletableFuture<Void> authentication = CompletableFuture.runAsync(
            () -> server.onMessage(connection, AUTH_MESSAGE));
        assertTrue(ackSendStarted.await(5, TimeUnit.SECONDS));

        try {
            assertEquals(0, server.getConnectionCount());
            assertEquals(0, server.getMetrics().get("totalConnections").getAsLong());
            JsonObject broadcast = new JsonObject();
            broadcast.addProperty("type", "server_notice");
            assertEquals(0, server.broadcast(broadcast));
            heartbeat.run();
            verify(connection, never()).send(argThat(
                (String message) -> message.contains("\"type\":\"ping\"")));
            verify(connectionStateListener, never()).onDjConnect(anyString());
        } finally {
            releaseAckSend.countDown();
        }

        authentication.get(5, TimeUnit.SECONDS);
        assertEquals(1, server.getConnectionCount());
        verify(connectionStateListener).onDjConnect(connection.getRemoteSocketAddress().toString());
    }

    @Test
    void constructorRejectsNonLoopbackBindEvenWithAuthentication() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new VizWebSocketServer(
                plugin,
                "0.0.0.0",
                0,
                messageHandler,
                messageQueue,
                new WebSocketSecurityPolicy("secret"),
                authTimeoutTasks::add
            )
        );
    }

    @Test
    void failedAuthOkSendNeverAdmitsClient() {
        VizWebSocketServer server = newServer("secret");
        server.onOpen(connection, handshake);
        Runnable heartbeat = startAndCaptureHeartbeat(server);
        doThrow(new IllegalStateException("send failed")).when(connection).send(AUTH_OK);

        assertThrows(
            IllegalStateException.class,
            () -> server.onMessage(connection, AUTH_MESSAGE)
        );

        assertInactive(server);
        heartbeat.run();
        verify(connection, never()).send(argThat(
            (String message) -> message.contains("\"type\":\"ping\"")));
    }

    @Test
    void closeDuringAuthOkSendPreventsPostCloseAdmission() throws Exception {
        VizWebSocketServer server = newServer("secret");
        server.onOpen(connection, handshake);
        Runnable heartbeat = startAndCaptureHeartbeat(server);
        CountDownLatch ackSendStarted = new CountDownLatch(1);
        CountDownLatch releaseAckSend = new CountDownLatch(1);
        doAnswer(invocation -> {
            ackSendStarted.countDown();
            if (!releaseAckSend.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release auth_ok send");
            }
            return null;
        }).when(connection).send(AUTH_OK);

        CompletableFuture<Void> authentication = CompletableFuture.runAsync(
            () -> server.onMessage(connection, AUTH_MESSAGE));
        assertTrue(ackSendStarted.await(5, TimeUnit.SECONDS));
        AtomicReference<Thread> closeThread = new AtomicReference<>();
        CompletableFuture<Void> close = CompletableFuture.runAsync(() -> {
            closeThread.set(Thread.currentThread());
            server.onClose(connection, 1000, "closed", true);
        });
        awaitBlockedOrComplete(close, closeThread);

        try {
            assertFalse(close.isDone());
        } finally {
            releaseAckSend.countDown();
        }
        authentication.get(5, TimeUnit.SECONDS);
        close.get(5, TimeUnit.SECONDS);

        assertInactive(server);
        heartbeat.run();
        verify(connection, never()).send(argThat(
            (String message) -> message.contains("\"type\":\"ping\"")));
    }

    @Test
    void admittedClientEmitsConnectThenSingleDisconnect() {
        VizWebSocketServer server = newServer("secret");
        server.onOpen(connection, handshake);
        server.onMessage(connection, AUTH_MESSAGE);

        server.onClose(connection, 1000, "closed", true);
        server.onClose(connection, 1000, "closed", true);

        InOrder lifecycle = inOrder(connectionStateListener);
        lifecycle.verify(connectionStateListener)
            .onDjConnect(connection.getRemoteSocketAddress().toString());
        lifecycle.verify(connectionStateListener).onDjDisconnect("remote close (code 1000)");
        verify(connectionStateListener).onDjConnect(connection.getRemoteSocketAddress().toString());
        verify(connectionStateListener).onDjDisconnect("remote close (code 1000)");
        assertEquals(0, server.getConnectionCount());
        assertEquals(1, server.getMetrics().get("totalConnections").getAsLong());
        assertEquals(1, server.getMetrics().get("totalDisconnections").getAsLong());
    }

    @Test
    void crossClientCloseAndAdmissionKeepGlobalLifecycleOrdered() throws Exception {
        BlockingLifecycleListener lifecycleListener = new BlockingLifecycleListener(plugin);
        when(plugin.getConnectionStateListener()).thenReturn(lifecycleListener);
        VizWebSocketServer server = newServer("secret");
        server.onOpen(connection, handshake);
        server.onMessage(connection, AUTH_MESSAGE);
        lifecycleListener.beginRace();

        CompletableFuture<Void> firstClose = CompletableFuture.runAsync(
            () -> server.onClose(connection, 1000, "first closed", true));
        assertTrue(lifecycleListener.disconnectEntered.await(5, TimeUnit.SECONDS));

        WebSocket secondConnection = org.mockito.Mockito.mock(WebSocket.class);
        ClientHandshake secondHandshake = org.mockito.Mockito.mock(ClientHandshake.class);
        when(secondConnection.getRemoteSocketAddress())
            .thenReturn(new InetSocketAddress("127.0.0.1", 54322));
        when(secondConnection.isOpen()).thenReturn(true);
        server.onOpen(secondConnection, secondHandshake);

        CountDownLatch secondAckSent = new CountDownLatch(1);
        doAnswer(invocation -> {
            secondAckSent.countDown();
            return null;
        }).when(secondConnection).send(AUTH_OK);
        AtomicReference<Thread> secondAuthThread = new AtomicReference<>();
        CompletableFuture<Void> secondAuthentication = CompletableFuture.runAsync(() -> {
            secondAuthThread.set(Thread.currentThread());
            server.onMessage(secondConnection, AUTH_MESSAGE);
        });
        assertTrue(secondAckSent.await(5, TimeUnit.SECONDS));

        awaitBlockedOrComplete(secondAuthentication, secondAuthThread);
        lifecycleListener.releaseDisconnect.countDown();
        firstClose.get(5, TimeUnit.SECONDS);
        secondAuthentication.get(5, TimeUnit.SECONDS);

        assertEquals(List.of(
                "disconnect:remote close (code 1000)",
                "connect:/127.0.0.1:54322"),
            lifecycleListener.events);
        assertTrue(lifecycleListener.connected.get());
        assertEquals(1, lifecycleListener.connectCount.get());
        assertEquals(1, lifecycleListener.disconnectCount.get());
        assertEquals(1, server.getConnectionCount());
        assertEquals(2, server.getMetrics().get("totalConnections").getAsLong());
        assertEquals(1, server.getMetrics().get("totalDisconnections").getAsLong());

        Runnable heartbeat = startAndCaptureHeartbeat(server);
        heartbeat.run();
        verify(connection, never()).send(argThat(
            (String message) -> message.contains("\"type\":\"ping\"")));
        verify(secondConnection).send(argThat(
            (String message) -> message.contains("\"type\":\"ping\"")));
    }

    @Test
    void oversizedAuthenticationMessageIsRejectedBeforeAdmission() {
        VizWebSocketServer server = newServer("secret");
        server.onOpen(connection, handshake);
        clearInvocations(connection);
        String oversizedAuth = "{\"type\":\"auth\",\"token\":\"secret\",\"padding\":\"" +
            "x".repeat(262_144) + "\"}";

        server.onMessage(connection, oversizedAuth);

        verify(connection).close(4001, "Authentication failed");
        verify(connection, never()).send("{\"type\":\"auth_ok\"}");
        assertEquals(0, server.getConnectionCount());
        verify(connectionStateListener, never()).onDjConnect(anyString());
    }

    @Test
    void capturedAuthenticationTimeoutClosesPendingClient() {
        VizWebSocketServer server = newServer("secret");
        server.onOpen(connection, handshake);

        authTimeoutTasks.getFirst().run();

        verify(connection).close(4002, "Authentication timeout");
        server.onClose(connection, 4002, "Authentication timeout", false);
        assertInactive(server);
    }

    @Test
    void anyOriginHeaderIsRejectedBeforeWelcomeEvenOnLoopback() {
        when(handshake.hasFieldValue("Origin")).thenReturn(true);
        VizWebSocketServer server = newServer("");

        server.onOpen(connection, handshake);

        verify(connection).close(4003, "Browser clients are not allowed");
        verify(connection, never()).send(anyString());
        server.onClose(connection, 4003, "Browser clients are not allowed", false);
        assertInactive(server);
    }

    @Test
    void serverLevelErrorBeforeStartFailsStartupCompletion() {
        VizWebSocketServer server = newServer("");
        IllegalStateException failure = new IllegalStateException("bind failed");

        server.onError(null, failure);

        var completionFailure = assertThrows(
            java.util.concurrent.CompletionException.class,
            () -> server.startupCompletion().toCompletableFuture().join()
        );
        assertEquals(failure, completionFailure.getCause());
    }

    @Test
    void startupCompletionObservationCannotCompleteServerStartup() {
        VizWebSocketServer server = newServer("");

        server.startupCompletion().toCompletableFuture().complete(null);

        assertFalse(server.startupCompletion().toCompletableFuture().isDone());
    }

    @Test
    void shutdownCancelsPendingStartupAndLateOnStartCannotScheduleTasks() throws Exception {
        VizWebSocketServer server = spy(newServer(""));
        doAnswer(invocation -> null).when(server).stop(3000);

        server.shutdown();
        server.onStart();

        var startup = server.startupCompletion().toCompletableFuture();
        assertTrue(startup.isCompletedExceptionally());
        var cancellation = assertThrows(
            java.util.concurrent.CompletionException.class,
            startup::join
        );
        assertTrue(cancellation.getCause() instanceof java.util.concurrent.CancellationException);
        verifyNoInteractions(scheduler);
        verify(messageQueue).stop();
    }

    @Test
    void confirmedStartCompletesStartupAndSchedulesHeartbeatAndMetrics() {
        when(plugin.getServer()).thenReturn(bukkitServer);
        when(bukkitServer.getScheduler()).thenReturn(scheduler);
        VizWebSocketServer server = newServer("");

        server.onStart();

        var startup = server.startupCompletion().toCompletableFuture();
        assertTrue(startup.isDone());
        assertFalse(startup.isCompletedExceptionally());
        verify(scheduler).runTaskTimerAsynchronously(
            eq(plugin), any(Runnable.class), eq(300L), eq(300L));
        verify(scheduler).runTaskTimerAsynchronously(
            eq(plugin), any(Runnable.class), eq(6000L), eq(6000L));
    }

    @Test
    void schedulerFailureFailsStartupAndCancelsPartiallyScheduledTasks() {
        when(plugin.getServer()).thenReturn(bukkitServer);
        when(bukkitServer.getScheduler()).thenReturn(scheduler);
        BukkitTask heartbeatTask = org.mockito.Mockito.mock(BukkitTask.class);
        when(scheduler.runTaskTimerAsynchronously(
            eq(plugin), any(Runnable.class), eq(300L), eq(300L)
        )).thenReturn(heartbeatTask);
        RejectedExecutionException failure = new RejectedExecutionException("plugin disabled");
        when(scheduler.runTaskTimerAsynchronously(
            eq(plugin), any(Runnable.class), eq(6000L), eq(6000L)
        )).thenThrow(failure);
        VizWebSocketServer server = newServer("");

        assertThrows(RejectedExecutionException.class, server::onStart);

        var startup = server.startupCompletion().toCompletableFuture();
        assertTrue(startup.isCompletedExceptionally());
        var completionFailure = assertThrows(
            java.util.concurrent.CompletionException.class,
            startup::join
        );
        assertEquals(failure, completionFailure.getCause());
        verify(heartbeatTask).cancel();
    }

    @Test
    void heartbeatAndBroadcastExcludePendingClientsUntilAuthentication() {
        when(plugin.getServer()).thenReturn(bukkitServer);
        when(bukkitServer.getScheduler()).thenReturn(scheduler);
        VizWebSocketServer server = newServer("secret");
        server.onOpen(connection, handshake);
        server.onStart();

        ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskTimerAsynchronously(
            eq(plugin), heartbeat.capture(), eq(300L), eq(300L));

        clearInvocations(connection);
        heartbeat.getValue().run();
        verify(connection, never()).send(anyString());

        server.onMessage(connection, "{\"type\":\"auth\",\"token\":\"secret\"}");
        clearInvocations(connection);
        heartbeat.getValue().run();

        JsonObject ping = singleSentMessage();
        assertEquals("ping", ping.get("type").getAsString());

        clearInvocations(connection);
        JsonObject broadcast = new JsonObject();
        broadcast.addProperty("type", "server_notice");
        assertEquals(1, server.broadcast(broadcast));
        assertEquals("server_notice", singleSentMessage().get("type").getAsString());
    }

    @Test
    void concurrentHandlersCanReenterBroadcastWithoutCrossClientDeadlock() throws Exception {
        WebSocket secondConnection = mockConnection(54322);
        ClientHandshake secondHandshake = org.mockito.Mockito.mock(ClientHandshake.class);
        MessageQueue realQueue = new MessageQueue(plugin, messageHandler);
        VizWebSocketServer server = newServer("", realQueue);
        CountDownLatch handlersEntered = new CountDownLatch(2);
        CountDownLatch handlersCompleted = new CountDownLatch(2);
        JsonObject notice = new JsonObject();
        notice.addProperty("type", "server_notice");
        doAnswer(invocation -> {
            try {
                handlersEntered.countDown();
                if (!handlersEntered.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting for concurrent handlers");
                }
                assertEquals(2, server.broadcast(notice));
                return null;
            } finally {
                handlersCompleted.countDown();
            }
        }).when(messageHandler).handleMessage(anyString(), any(JsonObject.class));

        try {
            server.onOpen(connection, handshake);
            server.onOpen(secondConnection, secondHandshake);
            clearInvocations(connection, secondConnection);

            server.onMessage(connection, "{\"type\":\"get_status\"}");
            server.onMessage(secondConnection, "{\"type\":\"get_status\"}");

            assertTrue(handlersCompleted.await(5, TimeUnit.SECONDS));
        } finally {
            realQueue.stop();
        }
    }

    @Test
    void slowClientCloseDoesNotConvoyUnrelatedAdmission() throws Exception {
        MessageQueue realQueue = new MessageQueue(plugin, messageHandler);
        VizWebSocketServer server = newServer("", realQueue);
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        doAnswer(invocation -> {
            handlerEntered.countDown();
            if (!releaseHandler.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release first client handler");
            }
            return null;
        }).when(messageHandler).handleMessage(anyString(), any(JsonObject.class));

        try {
            server.onOpen(connection, handshake);
            server.onMessage(connection, "{\"type\":\"get_status\"}");
            assertTrue(handlerEntered.await(5, TimeUnit.SECONDS));
            AtomicReference<Thread> closeThread = new AtomicReference<>();
            CompletableFuture<Void> close = CompletableFuture.runAsync(() -> {
                closeThread.set(Thread.currentThread());
                server.onClose(connection, 1000, "closed", true);
            });
            awaitBlockedOrComplete(close, closeThread);

            WebSocket secondConnection = mockConnection(54322);
            ClientHandshake secondHandshake = org.mockito.Mockito.mock(ClientHandshake.class);
            CompletableFuture<Void> admission = CompletableFuture.runAsync(
                () -> server.onOpen(secondConnection, secondHandshake));
            boolean admittedWithoutWaitingForFirstClient;
            try {
                admission.get(1, TimeUnit.SECONDS);
                admittedWithoutWaitingForFirstClient = true;
            } catch (TimeoutException exception) {
                admittedWithoutWaitingForFirstClient = false;
            } finally {
                releaseHandler.countDown();
            }

            close.get(5, TimeUnit.SECONDS);
            admission.get(5, TimeUnit.SECONDS);
            assertTrue(admittedWithoutWaitingForFirstClient);
            assertEquals(1, server.getConnectionCount());
        } finally {
            releaseHandler.countDown();
            realQueue.stop();
        }
    }

    @Test
    void clientRemainsAdmittingUntilConnectCallbackCompletes() throws Exception {
        CountDownLatch connectEntered = new CountDownLatch(1);
        CountDownLatch releaseConnect = new CountDownLatch(1);
        doAnswer(invocation -> {
            connectEntered.countDown();
            if (!releaseConnect.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release connect callback");
            }
            return null;
        }).when(connectionStateListener).onDjConnect(anyString());
        VizWebSocketServer server = newServer("");

        CompletableFuture<Void> open = CompletableFuture.runAsync(
            () -> server.onOpen(connection, handshake));
        assertTrue(connectEntered.await(5, TimeUnit.SECONDS));
        try {
            assertEquals(0, server.getConnectionCount());
            server.onMessage(connection, BATCH_UPDATE);
            verify(messageQueue, never()).parseAndDispatch(
                eq(BATCH_UPDATE),
                any(MessageQueue.MessageGuard.class),
                any(MessageQueue.ParsedMessageAdmission.class),
                any(MessageQueue.ParseFailureHandler.class)
            );
            verify(connection, never()).close(4001, "Authentication failed");
        } finally {
            releaseConnect.countDown();
        }

        open.get(5, TimeUnit.SECONDS);
        assertEquals(1, server.getConnectionCount());
        server.onMessage(connection, BATCH_UPDATE);
        verify(messageQueue).parseAndDispatch(
            eq(BATCH_UPDATE),
            any(MessageQueue.MessageGuard.class),
            any(MessageQueue.ParsedMessageAdmission.class),
            any(MessageQueue.ParseFailureHandler.class)
        );
    }

    @Test
    void shutdownWaitsForAcceptedWelcomeSendBeforeTeardown() throws Exception {
        VizWebSocketServer server = spy(newServer(""));
        doAnswer(invocation -> null).when(server).stop(3000);
        CountDownLatch welcomeSendStarted = new CountDownLatch(1);
        CountDownLatch releaseWelcomeSend = new CountDownLatch(1);
        doAnswer(invocation -> {
            welcomeSendStarted.countDown();
            if (!releaseWelcomeSend.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release welcome send");
            }
            return null;
        }).when(connection).send(argThat(
            (String message) -> message.contains("\"type\":\"connected\"")));

        CompletableFuture<Void> open = CompletableFuture.runAsync(
            () -> server.onOpen(connection, handshake));
        assertTrue(welcomeSendStarted.await(5, TimeUnit.SECONDS));
        AtomicReference<Thread> shutdownThread = new AtomicReference<>();
        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(() -> {
            shutdownThread.set(Thread.currentThread());
            server.shutdown();
        });
        awaitBlockedOrComplete(shutdown, shutdownThread);

        try {
            assertFalse(shutdown.isDone());
            verify(connection, never()).close(1001, "Server shutting down");
            verify(server, never()).stop(3000);
            verify(messageQueue, never()).stop();
        } finally {
            releaseWelcomeSend.countDown();
        }

        open.get(5, TimeUnit.SECONDS);
        shutdown.get(5, TimeUnit.SECONDS);
        verify(connection).close(1001, "Server shutting down");
        verify(server).stop(3000);
        verify(messageQueue).stop();
    }

    @Test
    void shutdownCancelsPendingMainThreadHandlerBeforeDrainingClientLease() throws Exception {
        VizWebSocketServer server = spy(newServer(""));
        doAnswer(invocation -> null).when(server).stop(3000);
        server.onOpen(connection, handshake);
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        when(messageHandler.handleMessage(eq("scan_stage_blocks"), any(JsonObject.class)))
            .thenAnswer(invocation -> {
                handlerEntered.countDown();
                if (!releaseHandler.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting for shutdown cancellation");
                }
                return null;
            });
        doAnswer(invocation -> {
            releaseHandler.countDown();
            return null;
        }).when(messageHandler).cancelPendingMainThreadCalls();

        String scan = "{\"type\":\"scan_stage_blocks\",\"stage\":\"main\"}";
        CompletableFuture<Void> handling = CompletableFuture.runAsync(
            () -> server.onMessage(connection, scan)
        );
        assertTrue(handlerEntered.await(1, TimeUnit.SECONDS));

        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(server::shutdown);
        try {
            shutdown.get(1, TimeUnit.SECONDS);
        } finally {
            releaseHandler.countDown();
        }
        handling.get(1, TimeUnit.SECONDS);

        verify(messageHandler).cancelPendingMainThreadCalls();
        verify(server).stop(3000);
        verify(messageQueue).stop();
    }

    @Test
    void authenticationFailureWaitsForAcceptedWelcomeBeforeClosingSocket() throws Exception {
        VizWebSocketServer server = newServer("secret");
        CountDownLatch welcomeSendStarted = new CountDownLatch(1);
        CountDownLatch releaseWelcomeSend = new CountDownLatch(1);
        doAnswer(invocation -> {
            welcomeSendStarted.countDown();
            if (!releaseWelcomeSend.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release welcome send");
            }
            return null;
        }).when(connection).send(argThat(
            (String message) -> message.contains("\"type\":\"connected\"")));

        CompletableFuture<Void> open = CompletableFuture.runAsync(
            () -> server.onOpen(connection, handshake));
        assertTrue(welcomeSendStarted.await(5, TimeUnit.SECONDS));
        AtomicReference<Thread> rejectionThread = new AtomicReference<>();
        CompletableFuture<Void> rejection = CompletableFuture.runAsync(() -> {
            rejectionThread.set(Thread.currentThread());
            server.onMessage(connection, "{}");
        });
        awaitBlockedOrComplete(rejection, rejectionThread);

        try {
            assertFalse(rejection.isDone());
            verify(connection, never()).close(4001, "Authentication failed");
        } finally {
            releaseWelcomeSend.countDown();
        }

        open.get(5, TimeUnit.SECONDS);
        rejection.get(5, TimeUnit.SECONDS);
        verify(connection).close(4001, "Authentication failed");
    }

    @Test
    void shutdownWaitsForAuthenticatedAuthOkSendBeforeTeardown() throws Exception {
        VizWebSocketServer server = spy(newServer("secret"));
        doAnswer(invocation -> null).when(server).stop(3000);
        server.onOpen(connection, handshake);
        clearInvocations(connection);
        CountDownLatch authOkSendStarted = new CountDownLatch(1);
        CountDownLatch releaseAuthOkSend = new CountDownLatch(1);
        doAnswer(invocation -> {
            authOkSendStarted.countDown();
            if (!releaseAuthOkSend.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release auth_ok send");
            }
            return null;
        }).when(connection).send(AUTH_OK);

        CompletableFuture<Void> authentication = CompletableFuture.runAsync(
            () -> server.onMessage(connection, AUTH_MESSAGE));
        assertTrue(authOkSendStarted.await(5, TimeUnit.SECONDS));
        AtomicReference<Thread> shutdownThread = new AtomicReference<>();
        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(() -> {
            shutdownThread.set(Thread.currentThread());
            server.shutdown();
        });
        awaitBlockedOrComplete(shutdown, shutdownThread);

        try {
            assertFalse(shutdown.isDone());
            verify(connection, never()).close(1001, "Server shutting down");
            verify(server, never()).stop(3000);
            verify(messageQueue, never()).stop();
        } finally {
            releaseAuthOkSend.countDown();
        }

        authentication.get(5, TimeUnit.SECONDS);
        shutdown.get(5, TimeUnit.SECONDS);
        verify(connection).close(1001, "Server shutting down");
        verify(server).stop(3000);
        verify(messageQueue).stop();
    }

    @Test
    void shutdownWaitsForInFlightThrowingHandlerAndDoesNotConvoyOtherClose() throws Exception {
        WebSocket secondConnection = mockConnection(54322);
        ClientHandshake secondHandshake = org.mockito.Mockito.mock(ClientHandshake.class);
        VizWebSocketServer server = spy(newServer(""));
        doAnswer(invocation -> null).when(server).stop(3000);
        server.onOpen(connection, handshake);
        server.onOpen(secondConnection, secondHandshake);

        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        doAnswer(invocation -> {
            handlerEntered.countDown();
            if (!releaseHandler.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release throwing handler");
            }
            throw new IllegalStateException("handler failed after admission");
        }).when(messageHandler).handleMessage(anyString(), any(JsonObject.class));

        CompletableFuture<Void> message = CompletableFuture.runAsync(
            () -> server.onMessage(connection, "{\"type\":\"get_status\"}"));
        assertTrue(handlerEntered.await(5, TimeUnit.SECONDS));
        AtomicReference<Thread> shutdownThread = new AtomicReference<>();
        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(() -> {
            shutdownThread.set(Thread.currentThread());
            server.shutdown();
        });
        awaitBlockedOrComplete(shutdown, shutdownThread);

        try {
            assertFalse(shutdown.isDone());
            verify(connection, never()).close(1001, "Server shutting down");
            verify(messageQueue, never()).stop();

            CompletableFuture<Void> unrelatedClose = CompletableFuture.runAsync(
                () -> server.onClose(secondConnection, 1000, "closed", true));
            unrelatedClose.get(1, TimeUnit.SECONDS);
        } finally {
            releaseHandler.countDown();
        }

        message.get(5, TimeUnit.SECONDS);
        shutdown.get(5, TimeUnit.SECONDS);
        verify(connection).close(1001, "Server shutting down");
        verify(messageQueue).stop();
    }

    @Test
    void shutdownWaitsForInFlightGuardedBatchBeforeTeardown() throws Exception {
        EntityPoolManager entityPoolManager = org.mockito.Mockito.mock(EntityPoolManager.class);
        ParticleVisualizationManager particleManager =
            org.mockito.Mockito.mock(ParticleVisualizationManager.class);
        ZoneManager zoneManager = org.mockito.Mockito.mock(ZoneManager.class);
        VisualizationZone zone = org.mockito.Mockito.mock(VisualizationZone.class);
        when(plugin.getEntityPoolManager()).thenReturn(entityPoolManager);
        when(plugin.getParticleVisualizationManager()).thenReturn(particleManager);
        when(plugin.getZoneManager()).thenReturn(zoneManager);
        when(particleManager.shouldRenderEntities("main")).thenReturn(true);
        when(zoneManager.getZone("main")).thenReturn(zone);
        when(zone.localToWorld(
            org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyDouble()
        )).thenReturn(org.mockito.Mockito.mock(Location.class));

        CountDownLatch batchEntered = new CountDownLatch(1);
        CountDownLatch releaseBatch = new CountDownLatch(1);
        doAnswer(invocation -> {
            batchEntered.countDown();
            if (!releaseBatch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release guarded batch");
            }
            return null;
        }).when(entityPoolManager).batchUpdateEntities(eq("main"), any());

        MessageQueue guardedQueue = spy(new MessageQueue(plugin, messageHandler));
        VizWebSocketServer server = spy(newServer("", guardedQueue));
        doAnswer(invocation -> null).when(server).stop(3000);
        server.onOpen(connection, handshake);
        server.onMessage(connection, BATCH_WITH_ENTITY);
        awaitQueueSize(guardedQueue, 1);

        CompletableFuture<Void> processing = CompletableFuture.runAsync(() -> {
            try {
                invokeProcessTick(guardedQueue);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        assertTrue(batchEntered.await(5, TimeUnit.SECONDS));
        AtomicReference<Thread> shutdownThread = new AtomicReference<>();
        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(() -> {
            shutdownThread.set(Thread.currentThread());
            server.shutdown();
        });
        awaitBlockedOrComplete(shutdown, shutdownThread);

        try {
            assertFalse(shutdown.isDone());
            verify(connection, never()).close(1001, "Server shutting down");
            verify(guardedQueue, never()).stop();
        } finally {
            releaseBatch.countDown();
        }

        processing.get(5, TimeUnit.SECONDS);
        shutdown.get(5, TimeUnit.SECONDS);
        verify(connection).close(1001, "Server shutting down");
        verify(guardedQueue).stop();
    }

    @Test
    void shutdownInvalidatesSessionAndDropsRealQueuedWorkWithoutCloseCallback() throws Exception {
        MessageQueue realQueue = new MessageQueue(plugin, messageHandler);
        VizWebSocketServer server = spy(newServer("", realQueue));
        doAnswer(invocation -> null).when(server).stop(3000);
        server.onOpen(connection, handshake);
        server.onMessage(connection, VOICE_AUDIO);
        awaitQueueSize(realQueue, 1);

        server.shutdown();

        assertEquals(0, server.getConnectionCount());
        verify(connection).close(1001, "Server shutting down");
        assertDoesNotThrow(() -> invokeProcessTick(realQueue));
        verify(messageHandler).cancelPendingMainThreadCalls();
        org.mockito.Mockito.verifyNoMoreInteractions(messageHandler);
    }

    @Test
    void messageQueueStopClearsParsedEntityAndCoalescedWork() throws Exception {
        EntityPoolManager entityPoolManager = org.mockito.Mockito.mock(EntityPoolManager.class);
        lenient().when(plugin.getEntityPoolManager()).thenReturn(entityPoolManager);
        MessageQueue realQueue = new MessageQueue(plugin, messageHandler);
        realQueue.enqueueRaw(VOICE_AUDIO);
        awaitQueueSize(realQueue, 1);

        ConcurrentLinkedQueue<EntityUpdate> entityQueue = privateField(
            realQueue,
            "entityUpdateQueue"
        );
        entityQueue.offer(org.mockito.Mockito.mock(EntityUpdate.class));
        Map<String, ArrayDeque<?>> batchCandidates = privateField(
            realQueue,
            "batchCandidatesByZone"
        );
        Map<String, ArrayDeque<?>> bitmapCandidates = privateField(
            realQueue,
            "bitmapCandidatesByZone"
        );
        batchCandidates.put("main", new ArrayDeque<>());
        bitmapCandidates.put("main", new ArrayDeque<>());

        realQueue.stop();

        assertTrue(batchCandidates.isEmpty());
        assertTrue(bitmapCandidates.isEmpty());
        assertDoesNotThrow(() -> invokeProcessTick(realQueue));
        verifyNoInteractions(messageHandler, entityPoolManager);
    }

    @Test
    void closeCannotOvertakeHighFrequencyQueueAcceptance() throws Exception {
        VizWebSocketServer server = newServer("");
        server.onOpen(connection, handshake);
        CountDownLatch enqueueEntered = new CountDownLatch(1);
        CountDownLatch releaseEnqueue = new CountDownLatch(1);
        doAnswer(invocation -> {
            enqueueEntered.countDown();
            if (!releaseEnqueue.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release queue acceptance");
            }
            return null;
        }).when(messageQueue).parseAndDispatch(
            eq(BATCH_UPDATE),
            any(MessageQueue.MessageGuard.class),
            any(MessageQueue.ParsedMessageAdmission.class),
            any(MessageQueue.ParseFailureHandler.class)
        );

        CompletableFuture<Void> message = CompletableFuture.runAsync(
            () -> server.onMessage(connection, BATCH_UPDATE));
        assertTrue(enqueueEntered.await(5, TimeUnit.SECONDS));

        AtomicReference<Thread> closeThread = new AtomicReference<>();
        CompletableFuture<Void> close = CompletableFuture.runAsync(() -> {
            closeThread.set(Thread.currentThread());
            server.onClose(connection, 1000, "closed", true);
        });
        awaitBlockedOrComplete(close, closeThread);

        try {
            assertFalse(close.isDone());
        } finally {
            releaseEnqueue.countDown();
        }

        message.get(5, TimeUnit.SECONDS);
        close.get(5, TimeUnit.SECONDS);
        assertEquals(0, server.getConnectionCount());
    }

    @Test
    void closeCannotOvertakeAcceptedHandlerExecution() throws Exception {
        VizWebSocketServer server = newServer("");
        server.onOpen(connection, handshake);
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        doAnswer(invocation -> {
            handlerEntered.countDown();
            if (!releaseHandler.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release handler");
            }
            return null;
        }).when(messageHandler).handleMessage(anyString(), any(JsonObject.class));

        CompletableFuture<Void> message = CompletableFuture.runAsync(
            () -> server.onMessage(connection, "{\"type\":\"get_status\"}"));
        assertTrue(handlerEntered.await(5, TimeUnit.SECONDS));

        AtomicReference<Thread> closeThread = new AtomicReference<>();
        CompletableFuture<Void> close = CompletableFuture.runAsync(() -> {
            closeThread.set(Thread.currentThread());
            server.onClose(connection, 1000, "closed", true);
        });
        awaitBlockedOrComplete(close, closeThread);

        try {
            assertFalse(close.isDone());
        } finally {
            releaseHandler.countDown();
        }

        message.get(5, TimeUnit.SECONDS);
        close.get(5, TimeUnit.SECONDS);
        assertEquals(0, server.getConnectionCount());
    }

    @Test
    void closedGenerationCannotExecuteParsedHighFrequencyWork() throws Exception {
        MessageQueue guardedQueue = new MessageQueue(plugin, messageHandler);
        VizWebSocketServer server = newServer("", guardedQueue);
        try {
            server.onOpen(connection, handshake);
            server.onMessage(connection, VOICE_AUDIO);
            awaitQueueSize(guardedQueue, 1);

            server.onClose(connection, 1000, "closed", true);
            server.onOpen(connection, handshake);
            invokeProcessTick(guardedQueue);

            verifyNoInteractions(messageHandler);
        } finally {
            guardedQueue.stop();
        }
    }

    @Test
    void closeCannotOvertakeAcceptedBatchApplication() throws Exception {
        EntityPoolManager entityPoolManager = org.mockito.Mockito.mock(EntityPoolManager.class);
        ParticleVisualizationManager particleManager =
            org.mockito.Mockito.mock(ParticleVisualizationManager.class);
        ZoneManager zoneManager = org.mockito.Mockito.mock(ZoneManager.class);
        VisualizationZone zone = org.mockito.Mockito.mock(VisualizationZone.class);
        when(plugin.getEntityPoolManager()).thenReturn(entityPoolManager);
        when(plugin.getParticleVisualizationManager()).thenReturn(particleManager);
        when(plugin.getZoneManager()).thenReturn(zoneManager);
        when(particleManager.shouldRenderEntities("main")).thenReturn(true);
        when(zoneManager.getZone("main")).thenReturn(zone);
        when(zone.localToWorld(
            org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyDouble()
        )).thenReturn(org.mockito.Mockito.mock(Location.class));

        CountDownLatch batchEntered = new CountDownLatch(1);
        CountDownLatch releaseBatch = new CountDownLatch(1);
        doAnswer(invocation -> {
            batchEntered.countDown();
            if (!releaseBatch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release batch application");
            }
            return null;
        }).when(entityPoolManager).batchUpdateEntities(eq("main"), any());

        MessageQueue guardedQueue = new MessageQueue(plugin, messageHandler);
        VizWebSocketServer server = newServer("", guardedQueue);
        try {
            server.onOpen(connection, handshake);
            server.onMessage(connection, BATCH_WITH_ENTITY);
            awaitQueueSize(guardedQueue, 1);

            CompletableFuture<Void> processing = CompletableFuture.runAsync(() -> {
                try {
                    invokeProcessTick(guardedQueue);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            assertTrue(batchEntered.await(5, TimeUnit.SECONDS));

            AtomicReference<Thread> closeThread = new AtomicReference<>();
            CompletableFuture<Void> close = CompletableFuture.runAsync(() -> {
                closeThread.set(Thread.currentThread());
                server.onClose(connection, 1000, "closed", true);
            });
            awaitBlockedOrComplete(close, closeThread);

            try {
                assertFalse(close.isDone());
            } finally {
                releaseBatch.countDown();
            }

            processing.get(5, TimeUnit.SECONDS);
            close.get(5, TimeUnit.SECONDS);
        } finally {
            releaseBatch.countDown();
            guardedQueue.stop();
        }
    }

    @Test
    void messageDuringShutdownCannotReachRejectedQueueExecutor() throws Exception {
        VizWebSocketServer server = spy(newServer(""));
        doAnswer(invocation -> null).when(server).stop(3000);
        server.onOpen(connection, handshake);
        lenient().doThrow(new RejectedExecutionException("queue stopped"))
            .when(messageQueue).parseAndDispatch(
                eq(BATCH_UPDATE),
                any(MessageQueue.MessageGuard.class),
                any(MessageQueue.ParsedMessageAdmission.class),
                any(MessageQueue.ParseFailureHandler.class)
            );

        server.shutdown();

        assertDoesNotThrow(() -> server.onMessage(connection, BATCH_UPDATE));
        verify(messageQueue, never()).parseAndDispatch(
            eq(BATCH_UPDATE),
            any(MessageQueue.MessageGuard.class),
            any(MessageQueue.ParsedMessageAdmission.class),
            any(MessageQueue.ParseFailureHandler.class)
        );
    }

    @Test
    void shutdownDrainsCrossedSubmissionBeforeCloseServerStopAndQueueStop() throws Exception {
        VizWebSocketServer server = spy(newServer(""));
        server.onOpen(connection, handshake);
        List<String> shutdownEvents = new CopyOnWriteArrayList<>();
        AtomicBoolean queueStopped = new AtomicBoolean();
        CountDownLatch enqueueEntered = new CountDownLatch(1);
        CountDownLatch releaseEnqueue = new CountDownLatch(1);
        doAnswer(invocation -> {
            enqueueEntered.countDown();
            if (!releaseEnqueue.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release crossed submission");
            }
            if (queueStopped.get()) {
                throw new RejectedExecutionException("queue stopped before submission drained");
            }
            return null;
        }).when(messageQueue).parseAndDispatch(
            eq(BATCH_UPDATE),
            any(MessageQueue.MessageGuard.class),
            any(MessageQueue.ParsedMessageAdmission.class),
            any(MessageQueue.ParseFailureHandler.class)
        );
        doAnswer(invocation -> {
            shutdownEvents.add("client-close");
            return null;
        }).when(connection).close(1001, "Server shutting down");
        doAnswer(invocation -> {
            shutdownEvents.add("server-stop");
            return null;
        }).when(server).stop(3000);
        doAnswer(invocation -> {
            queueStopped.set(true);
            shutdownEvents.add("queue-stop");
            return null;
        }).when(messageQueue).stop();

        CompletableFuture<Void> message = CompletableFuture.runAsync(
            () -> server.onMessage(connection, BATCH_UPDATE));
        assertTrue(enqueueEntered.await(5, TimeUnit.SECONDS));
        AtomicReference<Thread> shutdownThread = new AtomicReference<>();
        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(() -> {
            shutdownThread.set(Thread.currentThread());
            server.shutdown();
        });
        awaitBlockedOrComplete(shutdown, shutdownThread);

        try {
            assertFalse(shutdown.isDone());
            assertTrue(shutdownEvents.isEmpty());
        } finally {
            releaseEnqueue.countDown();
        }

        message.get(5, TimeUnit.SECONDS);
        shutdown.get(5, TimeUnit.SECONDS);
        assertEquals(
            List.of("client-close", "server-stop", "queue-stop"),
            shutdownEvents
        );
    }

    private VizWebSocketServer newServer(String secret) {
        return newServer(secret, messageQueue);
    }

    private VizWebSocketServer newServer(String secret, MessageQueue queue) {
        return new VizWebSocketServer(
            plugin,
            "127.0.0.1",
            0,
            messageHandler,
            queue,
            new WebSocketSecurityPolicy(secret),
            authTimeoutTasks::add
        );
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

    private static void invokeProcessTick(MessageQueue queue) throws Exception {
        var processTick = MessageQueue.class.getDeclaredMethod("processTick");
        processTick.setAccessible(true);
        processTick.invoke(queue);
    }

    @SuppressWarnings("unchecked")
    private static <T> T privateField(Object target, String fieldName) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static WebSocket mockConnection(int port) {
        WebSocket client = org.mockito.Mockito.mock(WebSocket.class);
        lenient().when(client.getRemoteSocketAddress())
            .thenReturn(new InetSocketAddress("127.0.0.1", port));
        lenient().when(client.isOpen()).thenReturn(true);
        return client;
    }

    private Runnable startAndCaptureHeartbeat(VizWebSocketServer server) {
        when(plugin.getServer()).thenReturn(bukkitServer);
        when(bukkitServer.getScheduler()).thenReturn(scheduler);
        server.onStart();

        ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskTimerAsynchronously(
            eq(plugin), heartbeat.capture(), eq(300L), eq(300L));
        return heartbeat.getValue();
    }

    private static void awaitBlockedOrComplete(
        CompletableFuture<Void> operation,
        AtomicReference<Thread> operationThread
    ) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!operation.isDone()) {
            Thread thread = operationThread.get();
            if (thread != null) {
                Thread.State state = thread.getState();
                if (state == Thread.State.BLOCKED
                        || state == Thread.State.WAITING
                        || state == Thread.State.TIMED_WAITING) {
                    return;
                }
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Second authentication neither completed nor blocked");
            }
            Thread.onSpinWait();
        }
    }

    private static final class BlockingLifecycleListener extends ConnectionStateListener {
        private final AtomicBoolean connected = new AtomicBoolean();
        private final AtomicInteger connectCount = new AtomicInteger();
        private final AtomicInteger disconnectCount = new AtomicInteger();
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch disconnectEntered = new CountDownLatch(1);
        private final CountDownLatch releaseDisconnect = new CountDownLatch(1);
        private volatile boolean blockDisconnect;

        private BlockingLifecycleListener(AudioVizPlugin plugin) {
            super(plugin);
        }

        private void beginRace() {
            events.clear();
            connectCount.set(0);
            disconnectCount.set(0);
            blockDisconnect = true;
        }

        @Override
        public void onDjConnect(String info) {
            events.add("connect:" + info);
            connectCount.incrementAndGet();
            connected.set(true);
        }

        @Override
        public void onDjDisconnect(String reason) {
            if (blockDisconnect) {
                disconnectEntered.countDown();
                try {
                    if (!releaseDisconnect.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release disconnect event");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while waiting to release disconnect", exception);
                }
            }
            events.add("disconnect:" + reason);
            disconnectCount.incrementAndGet();
            connected.set(false);
        }
    }

    private JsonObject sentMessage(String type) {
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(connection, org.mockito.Mockito.atLeastOnce()).send(messages.capture());
        return messages.getAllValues().stream()
            .map(JsonParser::parseString)
            .map(element -> element.getAsJsonObject())
            .filter(message -> type.equals(message.get("type").getAsString()))
            .findFirst()
            .orElseThrow();
    }

    private JsonObject singleSentMessage() {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(connection).send(message.capture());
        return JsonParser.parseString(message.getValue()).getAsJsonObject();
    }

    private void assertInactive(VizWebSocketServer server) {
        assertEquals(0, server.getConnectionCount());
        assertEquals(0, server.getMetrics().get("totalConnections").getAsLong());
        assertEquals(0, server.getMetrics().get("totalDisconnections").getAsLong());
        verify(connectionStateListener, never()).onDjConnect(anyString());
        verify(connectionStateListener, never()).onDjDisconnect(anyString());

        clearInvocations(connection);
        JsonObject broadcast = new JsonObject();
        broadcast.addProperty("type", "server_notice");
        assertEquals(0, server.broadcast(broadcast));
        verify(connection, never()).send(anyString());
    }
}
