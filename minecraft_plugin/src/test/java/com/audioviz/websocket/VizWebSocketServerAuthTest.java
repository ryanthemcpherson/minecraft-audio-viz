package com.audioviz.websocket;

import com.audioviz.AudioVizPlugin;
import com.audioviz.connection.ConnectionStateListener;
import com.audioviz.protocol.MessageHandler;
import com.audioviz.protocol.MessageQueue;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.scheduler.BukkitScheduler;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VizWebSocketServerAuthTest {

    private static final String BATCH_UPDATE = "{\"type\":\"batch_update\",\"updates\":[]}";
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

        when(plugin.getConfig()).thenReturn(config);
        when(config.getString("websocket.address", "127.0.0.1")).thenReturn("127.0.0.1");
        lenient().when(plugin.getLogger()).thenReturn(logger);
        lenient().when(plugin.getDescription()).thenReturn(description);
        lenient().when(description.getVersion()).thenReturn("test");
        lenient().when(plugin.getConnectionStateListener()).thenReturn(connectionStateListener);
        lenient().when(connection.getRemoteSocketAddress())
            .thenReturn(new InetSocketAddress("127.0.0.1", 54321));
        lenient().when(connection.isOpen()).thenReturn(true);
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
        verify(messageQueue).enqueueRaw(BATCH_UPDATE);

        server.onClose(connection, 1000, "closed", true);
        verify(connectionStateListener).onDjDisconnect("closed");
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
        verify(messageQueue, never()).enqueueRaw(anyString());
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
        verify(messageQueue).enqueueRaw(BATCH_UPDATE);
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
        server.onClose(connection, 1000, "closed", true);
        releaseAckSend.countDown();
        authentication.get(5, TimeUnit.SECONDS);

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
        lifecycle.verify(connectionStateListener).onDjDisconnect("closed");
        verify(connectionStateListener).onDjConnect(connection.getRemoteSocketAddress().toString());
        verify(connectionStateListener).onDjDisconnect("closed");
        assertEquals(0, server.getConnectionCount());
        assertEquals(1, server.getMetrics().get("totalConnections").getAsLong());
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

    private VizWebSocketServer newServer(String secret) {
        return new VizWebSocketServer(
            plugin,
            0,
            messageHandler,
            messageQueue,
            new WebSocketSecurityPolicy(secret),
            authTimeoutTasks::add
        );
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
