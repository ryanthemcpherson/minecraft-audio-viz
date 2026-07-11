package com.audioviz.websocket;

import com.audioviz.AudioVizMod;
import com.audioviz.connection.ConnectionStateListener;
import com.audioviz.protocol.MessageHandler;
import com.audioviz.protocol.MessageQueue;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VizWebSocketServerAuthTest {

    private static final String BATCH_UPDATE = "{\"type\":\"batch_update\",\"updates\":[]}";
    private static final String AUTH_MESSAGE = "{\"type\":\"auth\",\"token\":\"secret\"}";
    private static final String AUTH_OK = "{\"type\":\"auth_ok\"}";

    private MessageHandler messageHandler;
    private MessageQueue messageQueue;
    private Executor serverExecutor;
    private ConnectionStateListener connectionStateListener;
    private WebSocket connection;
    private ClientHandshake handshake;

    @BeforeEach
    void setUp() {
        messageHandler = mock(MessageHandler.class);
        messageQueue = mock(MessageQueue.class);
        serverExecutor = mock(Executor.class);
        connectionStateListener = mock(ConnectionStateListener.class);
        connection = mock(WebSocket.class);
        handshake = mock(ClientHandshake.class);
        lenient().when(connection.getRemoteSocketAddress())
            .thenReturn(new InetSocketAddress("127.0.0.1", 54321));
        lenient().when(connection.isOpen()).thenReturn(true);
    }

    @Test
    void secretlessLoopbackNativeClientIsAdmittedImmediately() {
        VizWebSocketServer server = newServer("");

        server.onOpen(connection, handshake);

        JsonObject welcome = sentMessage(connection, "connected");
        assertFalse(welcome.get("auth_required").getAsBoolean());
        assertEquals("fabric", welcome.get("server_type").getAsString());
        assertEquals(1, server.getConnectionCount());
        verify(connectionStateListener)
            .onDjConnect(connection.getRemoteSocketAddress().toString());

        server.onMessage(connection, BATCH_UPDATE);
        verify(messageQueue).enqueueRaw(
            eq(BATCH_UPDATE),
            any(MessageQueue.MessageGuard.class)
        );

        server.onClose(connection, 1000, "closed", true);
        verify(connectionStateListener).onDjDisconnect("remote close (code 1000)");
    }

    @Test
    void authenticatedConfigurationWelcomesClientAsPending() {
        VizWebSocketServer server = newServer("secret");

        server.onOpen(connection, handshake);

        JsonObject welcome = sentMessage(connection, "connected");
        assertTrue(welcome.get("auth_required").getAsBoolean());
        assertEquals(0, server.getConnectionCount());
        assertEquals(0, server.getMetrics().get("totalConnections").getAsLong());
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
        verify(messageQueue, never()).enqueueRaw(
            anyString(),
            any(MessageQueue.MessageGuard.class)
        );
        verify(serverExecutor, never()).execute(any(Runnable.class));
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
        List<String> logMessages = new CopyOnWriteArrayList<>();
        AbstractAppender appender = new AbstractAppender(
            "peer-close-secrecy-test",
            null,
            PatternLayout.createDefaultLayout(),
            false,
            null
        ) {
            @Override
            public void append(LogEvent event) {
                logMessages.add(event.getMessage().getFormattedMessage());
            }
        };
        org.apache.logging.log4j.core.Logger logger =
            (org.apache.logging.log4j.core.Logger) LogManager.getLogger(AudioVizMod.LOGGER.getName());
        appender.start();
        logger.addAppender(appender);
        server.onOpen(connection, handshake);

        try {
            server.onClose(connection, 4009, "SECRET_SENTINEL\r\nforged-log", true);
        } finally {
            logger.removeAppender(appender);
            appender.stop();
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

        server.onMessage(connection, AUTH_MESSAGE);

        InOrder admission = inOrder(connection, connectionStateListener);
        admission.verify(connection).send(AUTH_OK);
        admission.verify(connectionStateListener)
            .onDjConnect(connection.getRemoteSocketAddress().toString());
        assertEquals(1, server.getConnectionCount());

        server.onMessage(connection, BATCH_UPDATE);
        verify(messageQueue).enqueueRaw(
            eq(BATCH_UPDATE),
            any(MessageQueue.MessageGuard.class)
        );
    }

    @Test
    void remainsInactiveWhileAuthOkSendIsBlocked() throws Exception {
        VizWebSocketServer server = newServer("secret");
        server.onOpen(connection, handshake);
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
            verify(connectionStateListener, never()).onDjConnect(anyString());
        } finally {
            releaseAckSend.countDown();
        }

        authentication.get(5, TimeUnit.SECONDS);
        assertEquals(1, server.getConnectionCount());
        verify(connectionStateListener).onDjConnect(connection.getRemoteSocketAddress().toString());
    }

    @Test
    void remainsInvisibleUntilSerializedConnectCallbackCompletes() throws Exception {
        BlockingConnectLifecycleListener lifecycleListener =
            new BlockingConnectLifecycleListener();
        VizWebSocketServer server = newServer("secret", lifecycleListener);
        server.onOpen(connection, handshake);

        CompletableFuture<Void> authentication = CompletableFuture.runAsync(
            () -> server.onMessage(connection, AUTH_MESSAGE));
        assertTrue(lifecycleListener.connectEntered.await(5, TimeUnit.SECONDS));

        JsonObject notice = new JsonObject();
        notice.addProperty("type", "server_notice");
        AtomicReference<Thread> broadcastThread = new AtomicReference<>();
        CompletableFuture<Integer> broadcast = CompletableFuture.supplyAsync(() -> {
            broadcastThread.set(Thread.currentThread());
            return server.broadcast(notice);
        });
        awaitBlockedOrComplete(broadcast, broadcastThread);

        try {
            assertEquals(0, server.getConnectionCount());
            assertEquals(0, server.getMetrics().get("totalConnections").getAsLong());
            assertTrue(broadcast.isDone());
            assertEquals(0, broadcast.get(5, TimeUnit.SECONDS));
        } finally {
            lifecycleListener.releaseConnect.countDown();
        }

        authentication.get(5, TimeUnit.SECONDS);
        assertEquals(1, server.getConnectionCount());
    }

    @Test
    void failedAuthOkSendNeverAdmitsClient() {
        VizWebSocketServer server = newServer("secret");
        server.onOpen(connection, handshake);
        doThrow(new IllegalStateException("send failed")).when(connection).send(AUTH_OK);

        assertThrows(
            IllegalStateException.class,
            () -> server.onMessage(connection, AUTH_MESSAGE)
        );

        assertInactive(server, connectionStateListener, connection);
        runTicks(server, 300);
        verify(connection, never()).send(argThat(
            (String message) -> message.contains("\"type\":\"ping\"")));
    }

    @Test
    void closeDuringAuthOkSendPreventsPostCloseAdmission() throws Exception {
        VizWebSocketServer server = newServer("secret");
        server.onOpen(connection, handshake);
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
        server.onClose(connection, 1000, "closed", true);
        releaseAckSend.countDown();
        authentication.get(5, TimeUnit.SECONDS);

        assertInactive(server, connectionStateListener, connection);
        runTicks(server, 300);
        verify(connection, never()).send(argThat(
            (String message) -> message.contains("\"type\":\"ping\"")));
    }

    @Test
    void timeoutClosesPendingClientAfterExactlyFiveSecondsOfTicks() {
        VizWebSocketServer server = newServer("secret");
        server.onOpen(connection, handshake);

        runTicks(server, 99);
        verify(connection, never()).close(4002, "Authentication timeout");

        server.tick();

        verify(connection).close(4002, "Authentication timeout");
        server.onClose(connection, 4002, "Authentication timeout", false);
        assertInactive(server, connectionStateListener, connection);
    }

    @Test
    void anyOriginHeaderIsRejectedBeforeWelcomeEvenOnLoopback() {
        when(handshake.hasFieldValue("Origin")).thenReturn(true);
        VizWebSocketServer server = newServer("");

        server.onOpen(connection, handshake);

        verify(connection).close(4003, "Browser clients are not allowed");
        verify(connection, never()).send(anyString());
        server.onClose(connection, 4003, "Browser clients are not allowed", false);
        assertInactive(server, connectionStateListener, connection);
    }

    @Test
    void pendingClientReceivesNeitherHeartbeatNorBroadcast() {
        VizWebSocketServer server = newServer("secret");
        server.onOpen(connection, handshake);
        clearInvocations(connection);

        JsonObject broadcast = new JsonObject();
        broadcast.addProperty("type", "server_notice");
        assertEquals(0, server.broadcast(broadcast));
        runTicks(server, 300);

        verify(connection, never()).send(anyString());
        verify(connection).close(4002, "Authentication timeout");
    }

    @Test
    void heartbeatAndBroadcastReachAuthenticatedClient() {
        VizWebSocketServer server = newServer("secret");
        server.onOpen(connection, handshake);
        server.onMessage(connection, AUTH_MESSAGE);
        clearInvocations(connection);

        runTicks(server, 300);

        JsonObject ping = singleSentMessage(connection);
        assertEquals("ping", ping.get("type").getAsString());

        clearInvocations(connection);
        JsonObject broadcast = new JsonObject();
        broadcast.addProperty("type", "server_notice");
        assertEquals(1, server.broadcast(broadcast));
        assertEquals("server_notice", singleSentMessage(connection).get("type").getAsString());
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
        }).when(messageQueue).enqueueRaw(
            eq(BATCH_UPDATE),
            any(MessageQueue.MessageGuard.class)
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
        verify(messageQueue).enqueueRaw(
            eq(BATCH_UPDATE),
            any(MessageQueue.MessageGuard.class)
        );
        assertEquals(0, server.getConnectionCount());
    }

    @Test
    void closeCannotOvertakeAcceptedHandlerExecution() throws Exception {
        VizWebSocketServer server = newServer("");
        server.onOpen(connection, handshake);
        server.onMessage(connection, "{\"type\":\"get_status\"}");
        ArgumentCaptor<Runnable> handlerTask = ArgumentCaptor.forClass(Runnable.class);
        verify(serverExecutor).execute(handlerTask.capture());
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        doAnswer(invocation -> {
            handlerEntered.countDown();
            if (!releaseHandler.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release handler");
            }
            return null;
        }).when(messageHandler).handleMessage(anyString(), any(JsonObject.class));

        CompletableFuture<Void> handler = CompletableFuture.runAsync(handlerTask.getValue());
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

        handler.get(5, TimeUnit.SECONDS);
        close.get(5, TimeUnit.SECONDS);
        assertEquals(0, server.getConnectionCount());
    }

    @Test
    void closeCannotOvertakeAcceptedHeartbeatSend() throws Exception {
        VizWebSocketServer server = newServer("");
        server.onOpen(connection, handshake);
        clearInvocations(connection);
        CountDownLatch heartbeatReadyToSend = new CountDownLatch(1);
        CountDownLatch releaseHeartbeat = new CountDownLatch(1);
        doAnswer(invocation -> {
            heartbeatReadyToSend.countDown();
            if (!releaseHeartbeat.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release heartbeat");
            }
            return true;
        }).when(connection).isOpen();

        CompletableFuture<Void> heartbeat = CompletableFuture.runAsync(
            () -> runTicks(server, 300));
        assertTrue(heartbeatReadyToSend.await(5, TimeUnit.SECONDS));

        AtomicReference<Thread> closeThread = new AtomicReference<>();
        CompletableFuture<Void> close = CompletableFuture.runAsync(() -> {
            closeThread.set(Thread.currentThread());
            server.onClose(connection, 1000, "closed", true);
        });
        awaitBlockedOrComplete(close, closeThread);

        try {
            assertFalse(close.isDone());
        } finally {
            releaseHeartbeat.countDown();
        }

        heartbeat.get(5, TimeUnit.SECONDS);
        close.get(5, TimeUnit.SECONDS);
        verify(connection).send(argThat(
            (String message) -> message.contains("\"type\":\"ping\"")));
    }

    @Test
    void closeCannotOvertakeAcceptedBroadcastSend() throws Exception {
        VizWebSocketServer server = newServer("");
        server.onOpen(connection, handshake);
        clearInvocations(connection);
        CountDownLatch broadcastReadyToSend = new CountDownLatch(1);
        CountDownLatch releaseBroadcast = new CountDownLatch(1);
        doAnswer(invocation -> {
            broadcastReadyToSend.countDown();
            if (!releaseBroadcast.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release broadcast");
            }
            return true;
        }).when(connection).isOpen();
        JsonObject notice = new JsonObject();
        notice.addProperty("type", "server_notice");

        CompletableFuture<Integer> broadcast = CompletableFuture.supplyAsync(
            () -> server.broadcast(notice));
        assertTrue(broadcastReadyToSend.await(5, TimeUnit.SECONDS));

        AtomicReference<Thread> closeThread = new AtomicReference<>();
        CompletableFuture<Void> close = CompletableFuture.runAsync(() -> {
            closeThread.set(Thread.currentThread());
            server.onClose(connection, 1000, "closed", true);
        });
        awaitBlockedOrComplete(close, closeThread);

        try {
            assertFalse(close.isDone());
        } finally {
            releaseBroadcast.countDown();
        }

        assertEquals(1, broadcast.get(5, TimeUnit.SECONDS));
        close.get(5, TimeUnit.SECONDS);
        assertEquals(0, server.getConnectionCount());
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
        BlockingLifecycleListener lifecycleListener = new BlockingLifecycleListener();
        VizWebSocketServer server = newServer("secret", lifecycleListener);
        server.onOpen(connection, handshake);
        server.onMessage(connection, AUTH_MESSAGE);
        lifecycleListener.beginRace();

        CompletableFuture<Void> firstClose = CompletableFuture.runAsync(
            () -> server.onClose(connection, 1000, "first closed", true));
        assertTrue(lifecycleListener.disconnectEntered.await(5, TimeUnit.SECONDS));

        WebSocket secondConnection = mock(WebSocket.class);
        ClientHandshake secondHandshake = mock(ClientHandshake.class);
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

        runTicks(server, 300);
        verify(connection, never()).send(argThat(
            (String message) -> message.contains("\"type\":\"ping\"")));
        verify(secondConnection).send(argThat(
            (String message) -> message.contains("\"type\":\"ping\"")));
    }

    @Test
    void secondClientAdmissionBypassesFirstClientCloseWaitingOnHandler() throws Exception {
        RecordingLifecycleListener lifecycleListener = new RecordingLifecycleListener();
        VizWebSocketServer server = newServer("", lifecycleListener);
        server.onOpen(connection, handshake);
        lifecycleListener.events.clear();

        server.onMessage(connection, "{\"type\":\"get_status\"}");
        ArgumentCaptor<Runnable> handlerTask = ArgumentCaptor.forClass(Runnable.class);
        verify(serverExecutor).execute(handlerTask.capture());
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        doAnswer(invocation -> {
            handlerEntered.countDown();
            if (!releaseHandler.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release first client handler");
            }
            return null;
        }).when(messageHandler).handleMessage(anyString(), any(JsonObject.class));
        CompletableFuture<Void> handler = CompletableFuture.runAsync(handlerTask.getValue());
        assertTrue(handlerEntered.await(5, TimeUnit.SECONDS));

        AtomicReference<Thread> closeThread = new AtomicReference<>();
        CompletableFuture<Void> firstClose = CompletableFuture.runAsync(() -> {
            closeThread.set(Thread.currentThread());
            server.onClose(connection, 1000, "first closed", true);
        });
        awaitBlockedOrComplete(firstClose, closeThread);
        assertFalse(firstClose.isDone());

        WebSocket secondConnection = mock(WebSocket.class);
        ClientHandshake secondHandshake = mock(ClientHandshake.class);
        when(secondConnection.getRemoteSocketAddress())
            .thenReturn(new InetSocketAddress("127.0.0.1", 54322));
        when(secondConnection.isOpen()).thenReturn(true);
        AtomicReference<Thread> admissionThread = new AtomicReference<>();
        CompletableFuture<Void> secondAdmission = CompletableFuture.runAsync(() -> {
            admissionThread.set(Thread.currentThread());
            server.onOpen(secondConnection, secondHandshake);
        });
        awaitBlockedOrComplete(secondAdmission, admissionThread);
        boolean admissionCompletedBeforeRelease = secondAdmission.isDone();

        releaseHandler.countDown();
        handler.get(5, TimeUnit.SECONDS);
        firstClose.get(5, TimeUnit.SECONDS);
        secondAdmission.get(5, TimeUnit.SECONDS);

        assertTrue(admissionCompletedBeforeRelease);
        assertEquals(List.of("connect:/127.0.0.1:54322"), lifecycleListener.events);
        assertEquals(1, server.getConnectionCount());
        assertEquals(2, server.getMetrics().get("totalConnections").getAsLong());
        assertEquals(1, server.getMetrics().get("totalDisconnections").getAsLong());
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
        verify(connection, never()).send(AUTH_OK);
        assertEquals(0, server.getConnectionCount());
        verify(connectionStateListener, never()).onDjConnect(anyString());
    }

    @Test
    void closedClientDoesNotReceiveDeferredHandlerResponse() {
        VizWebSocketServer server = newServer("");
        JsonObject response = new JsonObject();
        response.addProperty("type", "status");
        when(messageHandler.handleMessage(anyString(), any(JsonObject.class))).thenReturn(response);
        server.onOpen(connection, handshake);
        clearInvocations(connection);

        server.onMessage(connection, "{\"type\":\"get_status\"}");
        ArgumentCaptor<Runnable> handlerTask = ArgumentCaptor.forClass(Runnable.class);
        verify(serverExecutor).execute(handlerTask.capture());
        server.onClose(connection, 1000, "closed", true);

        handlerTask.getValue().run();

        verifyNoInteractions(messageHandler);
        verify(connection, never()).send(anyString());
    }

    @Test
    void replacedClientIdentityDoesNotRunStaleQueuedHandler() {
        VizWebSocketServer server = newServer("");
        server.onOpen(connection, handshake);
        server.onMessage(connection, "{\"type\":\"get_status\"}");
        ArgumentCaptor<Runnable> handlerTask = ArgumentCaptor.forClass(Runnable.class);
        verify(serverExecutor).execute(handlerTask.capture());

        server.onClose(connection, 1000, "closed", true);
        server.onOpen(connection, handshake);
        handlerTask.getValue().run();

        verifyNoInteractions(messageHandler);
    }

    @Test
    void closedGenerationCannotExecuteCapturedHighFrequencyQueueWork() {
        CapturingMessageQueue guardedQueue = new CapturingMessageQueue(messageHandler);
        VizWebSocketServer server = newServer(
            "",
            connectionStateListener,
            guardedQueue
        );
        try {
            server.onOpen(connection, handshake);
            server.onMessage(connection, BATCH_UPDATE);

            server.onClose(connection, 1000, "closed", true);
            server.onOpen(connection, handshake);
            guardedQueue.releaseCapturedMessage();
            guardedQueue.processTick();

            verifyNoInteractions(messageHandler);
        } finally {
            guardedQueue.stop();
        }
    }

    @Test
    void messageDuringShutdownCannotReachRejectedQueueExecutor() {
        VizWebSocketServer server = newServer("");
        server.onOpen(connection, handshake);
        doThrow(new RejectedExecutionException("queue stopped"))
            .when(messageQueue).enqueueRaw(
                anyString(),
                any(MessageQueue.MessageGuard.class)
            );

        server.shutdown();

        assertDoesNotThrow(() -> server.onMessage(connection, BATCH_UPDATE));
        verify(messageQueue, never()).enqueueRaw(
            eq(BATCH_UPDATE),
            any(MessageQueue.MessageGuard.class)
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
        }).when(messageQueue).enqueueRaw(
            eq(BATCH_UPDATE),
            any(MessageQueue.MessageGuard.class)
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

        assertFalse(shutdown.isDone());
        assertTrue(shutdownEvents.isEmpty());
        releaseEnqueue.countDown();

        message.get(5, TimeUnit.SECONDS);
        shutdown.get(5, TimeUnit.SECONDS);
        assertEquals(
            List.of("client-close", "server-stop", "queue-stop"),
            shutdownEvents
        );
    }

    private VizWebSocketServer newServer(String secret) {
        return newServer(secret, connectionStateListener);
    }

    private VizWebSocketServer newServer(
        String secret,
        ConnectionStateListener lifecycleListener
    ) {
        return newServer(secret, lifecycleListener, messageQueue);
    }

    private VizWebSocketServer newServer(
        String secret,
        ConnectionStateListener lifecycleListener,
        MessageQueue queue
    ) {
        return new VizWebSocketServer(
            "127.0.0.1",
            0,
            secret,
            messageHandler,
            queue,
            serverExecutor,
            lifecycleListener
        );
    }

    private static final class CapturingMessageQueue extends MessageQueue {
        private String capturedMessage;
        private MessageGuard capturedGuard;

        private CapturingMessageQueue(MessageHandler messageHandler) {
            super(messageHandler);
        }

        @Override
        public void enqueueRaw(String rawJson, MessageGuard guard) {
            capturedMessage = rawJson;
            capturedGuard = guard;
        }

        private void releaseCapturedMessage() {
            if (capturedMessage == null || capturedGuard == null) {
                throw new AssertionError("No guarded queue message was captured");
            }
            enqueue(JsonParser.parseString(capturedMessage).getAsJsonObject(), capturedGuard);
        }
    }

    private static void runTicks(VizWebSocketServer server, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            server.tick();
        }
    }

    private static void awaitBlockedOrComplete(
        CompletableFuture<?> operation,
        AtomicReference<Thread> operationThread
    ) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!operation.isDone()) {
            Thread thread = operationThread.get();
            if (thread != null && thread.getState() == Thread.State.BLOCKED) {
                return;
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
                    throw new AssertionError(
                        "Interrupted while waiting to release disconnect",
                        exception
                    );
                }
            }
            events.add("disconnect:" + reason);
            disconnectCount.incrementAndGet();
            connected.set(false);
        }
    }

    private static final class BlockingConnectLifecycleListener extends ConnectionStateListener {
        private final CountDownLatch connectEntered = new CountDownLatch(1);
        private final CountDownLatch releaseConnect = new CountDownLatch(1);

        @Override
        public void onDjConnect(String info) {
            connectEntered.countDown();
            try {
                if (!releaseConnect.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release connect callback");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(
                    "Interrupted while waiting to release connect callback",
                    exception
                );
            }
        }
    }

    private static final class RecordingLifecycleListener extends ConnectionStateListener {
        private final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void onDjConnect(String info) {
            events.add("connect:" + info);
        }

        @Override
        public void onDjDisconnect(String reason) {
            events.add("disconnect:" + reason);
        }
    }

    private static JsonObject sentMessage(WebSocket client, String type) {
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(client, atLeastOnce()).send(messages.capture());
        return messages.getAllValues().stream()
            .map(JsonParser::parseString)
            .map(element -> element.getAsJsonObject())
            .filter(message -> type.equals(message.get("type").getAsString()))
            .findFirst()
            .orElseThrow();
    }

    private static JsonObject singleSentMessage(WebSocket client) {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(client).send(message.capture());
        return JsonParser.parseString(message.getValue()).getAsJsonObject();
    }

    private static void assertInactive(
        VizWebSocketServer server,
        ConnectionStateListener lifecycleListener,
        WebSocket client
    ) {
        assertEquals(0, server.getConnectionCount());
        assertEquals(0, server.getMetrics().get("totalConnections").getAsLong());
        assertEquals(0, server.getMetrics().get("totalDisconnections").getAsLong());
        verify(lifecycleListener, never()).onDjConnect(anyString());
        verify(lifecycleListener, never()).onDjDisconnect(anyString());

        clearInvocations(client);
        JsonObject broadcast = new JsonObject();
        broadcast.addProperty("type", "server_notice");
        assertEquals(0, server.broadcast(broadcast));
        verify(client, never()).send(anyString());
    }
}
